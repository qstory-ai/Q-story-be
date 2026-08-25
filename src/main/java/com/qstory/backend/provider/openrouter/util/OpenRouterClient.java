package com.qstory.backend.provider.openrouter.util;
import com.qstory.backend.provider.openrouter.SynthesizedAudioStream;
import com.qstory.backend.provider.openrouter.SynthesizedAudio;
import com.qstory.backend.provider.openrouter.RouteDecision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.choicecopy.service.ChoiceCopyService;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.story.service.RoutePromptService;
import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.common.util.WavPcmUtil;
import com.qstory.backend.story.ActionFamily;
import com.qstory.backend.story.StoryContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/** providers/openrouter.mjs를 Java로 포팅한 것: chat/completions 라우트 플래닝 + audio/speech TTS(버퍼링 및 스트리밍). */
@Component
public class OpenRouterClient {

    private static final String BASE_URL = "https://openrouter.ai/api/v1";
    private static final int PCM_SAMPLE_RATE = 24_000;
    private static final int PCM_CHANNELS = 1;
    private static final int PCM_BIT_DEPTH = 16;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RouteResultValidator routeResultValidator;
    private final ChoiceCopyService choiceCopyService;
    private final RoutePromptService routePromptService;
    private final String apiKey;
    private final String llmModel;
    private final String ttsModel;
    private final String ttsVoice;
    private final String imageModel;

    public OpenRouterClient(
            HttpClient httpClient, ObjectMapper objectMapper, RouteResultValidator routeResultValidator,
            ChoiceCopyService choiceCopyService, RoutePromptService routePromptService,
            AppProperties config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.routeResultValidator = routeResultValidator;
        this.choiceCopyService = choiceCopyService;
        this.routePromptService = routePromptService;
        this.apiKey = config.providers().openRouter().apiKey();
        this.llmModel = config.providers().openRouter().llmModel();
        this.ttsModel = config.providers().openRouter().ttsModel();
        this.ttsVoice = config.providers().openRouter().ttsVoice();
        this.imageModel = config.providers().openRouter().imageModel();
    }

    public record PlanRequest(String transcript, StoryContext storyContext, int questionRound, boolean guaranteeAgencyChoice) {}

    public RouteDecision generatePlan(PlanRequest request, RequestDeadline deadline) {
        StoryContext storyContext = request.storyContext();
        List<String> actionFamilyIds = storyContext.actionFamilyIds();
        try {
            HttpRequest httpRequest = deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/chat/completions"))
                            .headers(baseHeaders())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    buildChatCompletionsBody(request, actionFamilyIds).getBytes(StandardCharsets.UTF_8))))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                boolean detailPresent = readErrorDetail(response.body()) != null;
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_RESPONSE_FAILED,
                        detailPresent ? "아이의 질문에 답을 준비하지 못했어요." : "AI 응답 서버에 연결하지 못했어요.",
                        response.statusCode() == 400 || response.statusCode() >= 429);
            }
            JsonNode payload = objectMapper.readTree(response.body());
            JsonNode contentNode = payload.path("choices").path(0).path("message").path("content");
            JsonNode parsed = contentNode.isTextual()
                    ? objectMapper.readTree(contentNode.asText())
                    : contentNode;
            RouteDecision routeResult = routeResultValidator.validateRouteResult(parsed, storyContext, llmModel);
            if (routeResult == null) {
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.");
            }
            if (request.questionRound() > 1 && "CLARIFY_ONCE".equals(routeResult.route())) {
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_SECOND_CLARIFICATION, "같은 질문을 다시 확인하지 않고 이야기로 돌아갈게요.");
            }
            RouteDecision coverageAligned = routeResultValidator.alignActionRouteCoverage(
                    routeResult, storyContext, request.transcript(), request.questionRound());
            RouteDecision concernAware = routeResultValidator.promoteConcernToChoice(
                    coverageAligned, storyContext, request.transcript(), request.questionRound());
            RouteDecision agencyAware = routeResultValidator.guaranteeBetaAgencyChoice(
                    concernAware, storyContext, request.transcript(), request.guaranteeAgencyChoice(), request.questionRound());
            return routeResultValidator.sanitizeGeneratedOptionCopy(
                    agencyAware, storyContext, request.transcript(), request.questionRound());
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.", true, error);
        }
    }

    public record CompanionRequest(
            String transcript,
            String promptVersion,
            String primarySpeakerId,
            List<String> allowedSpeakerIds,
            List<String> forbiddenKnowledge) {}

    public record CompanionReply(
            String interactionMode, String responseText, String speakerId,
            String topicTag, String toneTag, String valueTag) {}

    /**
     * 앵커에 독립적인 companion-chat 표면을 위한 generatePlan()의 형제 메서드: LLM 호출은 한 번뿐이며,
     * options/family/rejoin 개념 자체가 전혀 없다. 라우팅 프롬프트에 직접 작성된 안전 블록
     * (RoutePrompt.companionSafetyFragment, systemPrompt()/companionSystemPrompt() 참고)을 재사용하므로
     * 두 프롬프트가 무엇을 안전하지 않다고 볼지에 대해 서로 어긋나는 일이 없다.
     */
    public CompanionReply generateCompanionReply(CompanionRequest request, RequestDeadline deadline) {
        try {
            HttpRequest httpRequest = deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/chat/completions"))
                            .headers(baseHeaders())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    buildCompanionChatBody(request).getBytes(StandardCharsets.UTF_8))))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                boolean detailPresent = readErrorDetail(response.body()) != null;
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_RESPONSE_FAILED,
                        detailPresent ? "아이의 말에 답을 준비하지 못했어요." : "AI 응답 서버에 연결하지 못했어요.",
                        response.statusCode() == 400 || response.statusCode() >= 429);
            }
            JsonNode payload = objectMapper.readTree(response.body());
            JsonNode contentNode = payload.path("choices").path(0).path("message").path("content");
            JsonNode parsed = contentNode.isTextual()
                    ? objectMapper.readTree(contentNode.asText())
                    : contentNode;
            CompanionReply reply = validateCompanionReply(parsed, request);
            if (reply == null) {
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.");
            }
            return reply;
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.", true, error);
        }
    }

    private CompanionReply validateCompanionReply(JsonNode value, CompanionRequest request) {
        if (value == null || !value.isObject()) {
            return null;
        }
        String interactionMode = value.path("interactionMode").asText("").trim();
        if (!com.qstory.backend.common.enums.CompanionInteractionMode.ALL_NAMES.contains(interactionMode)) {
            return null;
        }
        String responseText = value.path("responseText").isTextual() ? value.get("responseText").asText().trim() : "";
        if (responseText.isEmpty() || responseText.length() > 160) {
            return null;
        }
        String speakerId = value.path("speakerId").asText("").trim();
        if (!request.allowedSpeakerIds().contains(speakerId)) {
            return null;
        }
        String topicTag = nullableEnumLabel(value.get("topicTag"),
                com.qstory.backend.common.enums.CompanionTopicTag.ALL_LABELS);
        String toneTag = nullableEnumLabel(value.get("toneTag"),
                com.qstory.backend.common.enums.CompanionToneTag.ALL_LABELS);
        String valueTag = nullableEnumLabel(value.get("valueTag"),
                com.qstory.backend.common.enums.CompanionValueTag.ALL_LABELS);
        return new CompanionReply(
                interactionMode, routeResultValidator.normalizeKoreanResponseText(responseText), speakerId,
                topicTag, toneTag, valueTag);
    }

    /** JSON null/누락 노드면 null을, 인식된 enum 값이면 그 라벨을, 그 외에는 NOT_FOUND를 반환한다(아래에서 거부됨). */
    private static final String NOT_FOUND = " ";

    private String nullableEnumLabel(JsonNode node, List<String> allowedLabels) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.isTextual() ? node.asText().trim() : NOT_FOUND;
        return allowedLabels.contains(text) ? text : NOT_FOUND;
    }

    private String buildCompanionChatBody(CompanionRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", llmModel);

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", companionSystemPrompt(request.promptVersion()));

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", companionUserPayload(request).toString());

        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", "q_story_companion_v1");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", companionSchema(request.allowedSpeakerIds()));

        root.putObject("provider").put("require_parameters", true);
        ObjectNode reasoning = root.putObject("reasoning");
        reasoning.put("effort", "minimal");
        reasoning.put("exclude", true);
        root.put("temperature", 0);
        root.put("max_tokens", 600);
        return root.toString();
    }

    private ObjectNode companionUserPayload(CompanionRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("childMessage", request.transcript());
        payload.put("primarySpeakerId", request.primarySpeakerId());
        ArrayNode allowedSpeakerIds = payload.putArray("allowedSpeakerIds");
        request.allowedSpeakerIds().forEach(allowedSpeakerIds::add);
        ArrayNode forbiddenKnowledge = payload.putArray("forbiddenKnowledge");
        request.forbiddenKnowledge().forEach(forbiddenKnowledge::add);
        return payload;
    }

    private ObjectNode companionSchema(List<String> allowedSpeakerIds) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode interactionMode = properties.putObject("interactionMode");
        interactionMode.put("type", "string");
        ArrayNode modeEnum = interactionMode.putArray("enum");
        com.qstory.backend.common.enums.CompanionInteractionMode.ALL_NAMES.forEach(modeEnum::add);

        addStringProperty(properties, "responseText", 1, 160);

        ObjectNode speakerId = properties.putObject("speakerId");
        speakerId.put("type", "string");
        ArrayNode speakerEnum = speakerId.putArray("enum");
        allowedSpeakerIds.forEach(speakerEnum::add);

        addNullableLabelEnum(properties, "topicTag", com.qstory.backend.common.enums.CompanionTopicTag.ALL_LABELS);
        addNullableLabelEnum(properties, "toneTag", com.qstory.backend.common.enums.CompanionToneTag.ALL_LABELS);
        addNullableLabelEnum(properties, "valueTag", com.qstory.backend.common.enums.CompanionValueTag.ALL_LABELS);

        ArrayNode required = schema.putArray("required");
        required.add("interactionMode").add("responseText").add("speakerId")
                .add("topicTag").add("toneTag").add("valueTag");
        schema.put("additionalProperties", false);
        return schema;
    }

    private void addNullableLabelEnum(ObjectNode properties, String name, List<String> labels) {
        ObjectNode node = properties.putObject(name);
        ArrayNode type = node.putArray("type");
        type.add("string").add("null");
        ArrayNode enumNode = node.putArray("enum");
        labels.forEach(enumNode::add);
        enumNode.addNull();
    }

    /**
     * v1 잠정(provisional) 컴패니언 페르소나 설정 - 라우팅 시스템 프롬프트처럼 route_prompt에
     * 작성되어 있지 않은데, 이는 라우팅 의사결정 트리가 아니기 때문이다; 안전 규칙 블록만 공유된다.
     */
    private String companionSystemPrompt(String promptVersion) {
        String safetyFragment = routePromptService.requirePrompt(promptVersion).companionSafetyFragment();
        return String.join(" ",
                "너는 6~9세 아이와 한국어 동화책 속 등장인물로서 다정하게 대화하는 친구다.",
                "아이가 무슨 말을 하든 그 자리에서 1~3문장으로, 캐릭터답게 반말로 자연스럽게 반응한다.",
                "새로운 분기나 선택지를 만들지 않는다 - 오직 대화일 뿐, 이야기 진행에는 영향을 주지 않는다.",
                "forbiddenKnowledge에 있는 내용은 사실·추측·가능성 형태로도 절대 언급하지 않는다 - 아직 일어나지 않은 이야기의 전개를 미리 알려주지 않는다.",
                safetyFragment != null ? safetyFragment : "",
                "위 규칙에 해당하면 interactionMode를 GENTLE_REDIRECT로 하고, 위험을 짧게 막은 뒤 안전한 화제로 부드럽게 돌아온다.",
                "그 외에는 interactionMode를 ANSWER로 한다.",
                "topicTag/toneTag/valueTag는 아이의 말에서 뚜렷하게 드러날 때만 고르고, 확신이 없으면 null로 둔다 - 아이 말을 그대로 반복하거나 확대 해석하지 않는다.",
                "응답을 반환하기 전에 한국어 맞춤법·띄어쓰기와 말투 일치를 한 번 확인한다.");
    }

    public SynthesizedAudio synthesize(String text, String voice, double speed, RequestDeadline deadline) {
        try {
            HttpRequest httpRequest = deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/audio/speech"))
                            .headers(baseHeaders())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(speechRequestBody(text, voice, speed))))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_TTS_FAILED, "답변 음성을 만들지 못했어요.", response.statusCode() >= 429);
            }
            byte[] rawAudio = response.body();
            if (rawAudio.length == 0) {
                throw new ProviderException(ProviderErrorCode.OPENROUTER_TTS_EMPTY, "답변 음성이 비어 있어요.");
            }
            String responseMimeType = response.headers().firstValue("content-type").orElse("audio/pcm");
            boolean isPcm = responseMimeType.contains("pcm") || responseMimeType.contains("L16")
                    || responseMimeType.equals("application/octet-stream");
            byte[] audio = isPcm
                    ? WavPcmUtil.wrapPcmAsWav(rawAudio, PCM_SAMPLE_RATE, PCM_CHANNELS, PCM_BIT_DEPTH)
                    : rawAudio;
            return new SynthesizedAudio(
                    audio, isPcm ? "audio/wav" : responseMimeType,
                    response.headers().firstValue("x-generation-id").orElse(null));
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_TTS_NETWORK_FAILED, "답변 음성 서버에 연결하지 못했어요.", true, error);
        }
    }

    public SynthesizedAudioStream synthesizeStream(String text, String voice, double speed, RequestDeadline deadline) {
        try {
            HttpRequest httpRequest = deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/audio/speech"))
                            .headers(baseHeaders())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(speechRequestBody(text, voice, speed))))
                    .build();
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_TTS_FAILED, "답변 음성을 만들지 못했어요.", response.statusCode() >= 429);
            }
            String responseMimeType = response.headers().firstValue("content-type").orElse("audio/pcm");
            boolean isPcm = responseMimeType.contains("pcm") || responseMimeType.contains("L16")
                    || responseMimeType.equals("application/octet-stream");
            if (!isPcm) {
                response.body().close();
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_TTS_STREAM_INVALID, "스트리밍 음성 형식을 확인하지 못했어요.");
            }
            return new SynthesizedAudioStream(
                    response.body(), "audio/pcm", PCM_SAMPLE_RATE, PCM_CHANNELS, PCM_BIT_DEPTH,
                    response.headers().firstValue("x-generation-id").orElse(null));
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_TTS_NETWORK_FAILED, "답변 음성 서버에 연결하지 못했어요.", true, error);
        }
    }

    /**
     * generatePlan()/generateCompanionReply()는 각자의 고정된 스키마(RouteDecision/CompanionReply)에
     * 강하게 결합돼 있어 재사용하기 어렵다. shadow-family 생성(대본 작성 + 별도의 자동 검수 게이트
     * 호출, 둘 다 서로 다른 JSON 스키마를 쓴다)을 위해, 두 메서드가 이미 하던 요청 조립·에러 처리
     * 배관(plumbing)만 일반화한 것이다 - 스키마 자체는 호출자가 만든다.
     */
    public JsonNode generateStructuredCompletion(
            String systemPrompt, String userPayloadJson, ObjectNode schema, String schemaName, int maxTokens,
            ProviderErrorCode failureCode, String failureSafeDetail, RequestDeadline deadline) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", llmModel);
            ArrayNode messages = root.putArray("messages");
            ObjectNode systemMessage = messages.addObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            ObjectNode userMessage = messages.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", userPayloadJson);
            ObjectNode responseFormat = root.putObject("response_format");
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", schemaName);
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", schema);
            root.putObject("provider").put("require_parameters", true);
            ObjectNode reasoning = root.putObject("reasoning");
            reasoning.put("effort", "minimal");
            reasoning.put("exclude", true);
            root.put("temperature", 0);
            root.put("max_tokens", maxTokens);

            HttpRequest httpRequest = deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/chat/completions"))
                            .headers(baseHeaders())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    root.toString().getBytes(StandardCharsets.UTF_8))))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new ProviderException(
                        failureCode, failureSafeDetail, response.statusCode() == 400 || response.statusCode() >= 429);
            }
            JsonNode payload = objectMapper.readTree(response.body());
            JsonNode contentNode = payload.path("choices").path(0).path("message").path("content");
            return contentNode.isTextual() ? objectMapper.readTree(contentNode.asText()) : contentNode;
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(failureCode, failureSafeDetail, true, error);
        }
    }

    public record GeneratedImage(byte[] bytes, String mimeType) {}

    /**
     * shadow-generation.mjs의 generateImage()를 포팅한 것 - 이 백엔드에는 이미지 생성 호출이 이전에
     * 전혀 없었다(TTS/구조화 채팅완성만 있었음). 참조 이미지 하나(기존 승인 삽화)를 스타일/인물
     * 고정용으로 함께 보낸다.
     */
    public GeneratedImage generateImage(
            String prompt, byte[] referenceImage, String referenceImageMimeType, RequestDeadline deadline) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", imageModel);
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("aspect_ratio", "16:9");
            body.put("output_format", "webp");
            ArrayNode references = body.putArray("input_references");
            ObjectNode reference = references.addObject();
            reference.put("type", "image_url");
            ObjectNode imageUrl = reference.putObject("image_url");
            imageUrl.put("url", "data:" + referenceImageMimeType + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(referenceImage));

            HttpRequest httpRequest = deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/images"))
                            .headers(baseHeaders())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    body.toString().getBytes(StandardCharsets.UTF_8))))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_IMAGE_FAILED, "삽화를 만들지 못했어요.", response.statusCode() >= 429);
            }
            JsonNode payload = objectMapper.readTree(response.body());
            String encoded = payload.path("data").path(0).path("b64_json").asText(null);
            if (encoded == null || encoded.length() < 500) {
                throw new ProviderException(ProviderErrorCode.OPENROUTER_IMAGE_EMPTY, "삽화가 비어 있어요.");
            }
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
            String mimeType = detectImageMimeType(bytes);
            if (mimeType == null || bytes.length > 10 * 1024 * 1024) {
                throw new ProviderException(ProviderErrorCode.OPENROUTER_IMAGE_INVALID, "삽화 형식을 확인하지 못했어요.");
            }
            return new GeneratedImage(bytes, mimeType);
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_IMAGE_NETWORK_FAILED, "삽화 생성 서버에 연결하지 못했어요.", true, error);
        }
    }

    private String detectImageMimeType(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private String[] baseHeaders() {
        return new String[] {
            "authorization", "Bearer " + apiKey,
            "content-type", "application/json",
            "http-referer", "https://q-story-f07-pilot.kaangaa.chatgpt.site",
            "x-openrouter-title", "Q-Story",
        };
    }

    private byte[] speechRequestBody(String text, String voice, double speed) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", ttsModel);
        body.put("input", text);
        body.put("voice", voice == null ? ttsVoice : voice);
        body.put("response_format", "pcm");
        body.put("speed", speed);
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String readErrorDetail(byte[] body) {
        try {
            JsonNode payload = objectMapper.readTree(body);
            JsonNode message = payload.path("error").path("message");
            if (message.isTextual()) {
                return message.asText();
            }
            JsonNode topLevelMessage = payload.path("message");
            return topLevelMessage.isTextual() ? topLevelMessage.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildChatCompletionsBody(PlanRequest request, List<String> actionFamilyIds) {
        StoryContext storyContext = request.storyContext();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", llmModel);

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt(storyContext.versions().promptVersion()));

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPayload(request, actionFamilyIds).toString());

        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", "q_story_route_v1");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", routeSchema(actionFamilyIds, storyContext.allowedSpeakerIds()));

        root.putObject("provider").put("require_parameters", true);
        ObjectNode reasoning = root.putObject("reasoning");
        reasoning.put("effort", "minimal");
        reasoning.put("exclude", true);
        root.put("temperature", 0);
        root.put("max_tokens", 1_200);
        return root.toString();
    }

    private ObjectNode userPayload(PlanRequest request, List<String> actionFamilyIds) {
        StoryContext storyContext = request.storyContext();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("promptVersion", storyContext.versions().promptVersion());
        payload.put("routePolicyVersion", storyContext.versions().routePolicyVersion());
        payload.put("currentScene", storyContext.summary());
        payload.put("childTranscript", request.transcript());
        payload.put("clarificationAlreadyUsed", request.questionRound() > 1);
        payload.put("primarySpeakerId", storyContext.primarySpeakerId());
        ArrayNode allowedSpeakerIds = payload.putArray("allowedSpeakerIds");
        storyContext.allowedSpeakerIds().forEach(allowedSpeakerIds::add);

        ArrayNode allowedActionFamilies = payload.putArray("allowedActionFamilies");
        for (ActionFamily family : storyContext.actionFamilies()) {
            ObjectNode node = allowedActionFamilies.addObject();
            node.put("id", family.id());
            node.put("meaning", family.meaning());
        }

        ArrayNode approvedChoiceCopyBank = payload.putArray("approvedChoiceCopyBank");
        for (var entry : choiceCopyService.choiceCopyBankForFamilies(storyContext.actionFamilies())) {
            ObjectNode node = approvedChoiceCopyBank.addObject();
            node.put("actionFamilyId", entry.actionFamilyId());
            ArrayNode examples = node.putArray("examples");
            entry.examples().forEach(example -> {
                ObjectNode exampleNode = examples.addObject();
                exampleNode.put("label", example.label());
                exampleNode.put("meaning", example.meaning());
            });
        }

        payload.put("fixedRejoinAnchorId", storyContext.rejoinAt());
        payload.put("fallbackFamilyId", storyContext.fallbackFamilyId());
        ArrayNode forbiddenKnowledge = payload.putArray("forbiddenKnowledge");
        storyContext.forbiddenKnowledge().forEach(forbiddenKnowledge::add);
        payload.put(
                "instruction",
                routePromptService.requirePrompt(storyContext.versions().promptVersion())
                        .instructionText());
        return payload;
    }

    private ObjectNode routeSchema(List<String> actionFamilyIds, List<String> routeSpeakerIds) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode route = properties.putObject("route");
        route.put("type", "string");
        ArrayNode routeEnum = route.putArray("enum");
        com.qstory.backend.common.enums.RouteKind.ALL_NAMES.forEach(routeEnum::add);

        addStringProperty(properties, "childRelevantMeaning", 1, 160);
        ObjectNode coverageStatus = properties.putObject("coverageStatus");
        coverageStatus.put("type", "string");
        ArrayNode coverageEnum = coverageStatus.putArray("enum");
        com.qstory.backend.common.enums.CoverageStatus.WIRE_VALUES.forEach(coverageEnum::add);
        addStringProperty(properties, "coverageReason", 1, 160);
        addStringProperty(properties, "responseText", 1, 120);

        ObjectNode speakerId = properties.putObject("speakerId");
        speakerId.put("type", "string");
        ArrayNode speakerEnum = speakerId.putArray("enum");
        routeSpeakerIds.forEach(speakerEnum::add);

        addNullableFamilyEnum(properties, "actionFamilyId", actionFamilyIds,
                "행동·장면변화·우회 route만 허용 family 하나. 단순 route와 THREE_PATHS는 반드시 null.");
        ObjectNode rejoinAnchorId = properties.putObject("rejoinAnchorId");
        rejoinAnchorId.putArray("type").add("string").add("null");
        rejoinAnchorId.put("description", "행동·장면변화·우회·THREE_PATHS는 제공된 fixedRejoinAnchorId. 단순 route는 반드시 null.");
        addNullableFamilyEnum(properties, "fallbackFamilyId", actionFamilyIds,
                "행동·장면변화·우회·THREE_PATHS는 제공된 fallbackFamilyId. 단순 route는 반드시 null.");

        ObjectNode options = properties.putObject("options");
        options.put("type", "array");
        options.put("minItems", 0);
        options.put("maxItems", 3);
        options.put("description", "THREE_PATHS만 정확히 3개. 그 밖의 모든 route는 반드시 빈 배열.");
        ObjectNode optionItems = options.putObject("items");
        optionItems.put("type", "object");
        ObjectNode optionProperties = optionItems.putObject("properties");
        ObjectNode optionId = optionProperties.putObject("id");
        optionId.put("type", "string");
        ArrayNode optionIdEnum = optionId.putArray("enum");
        optionIdEnum.add("OPTION_1").add("OPTION_2").add("OPTION_3");
        addStringProperty(optionProperties, "label", 1, 18);
        addStringProperty(optionProperties, "meaning", 1, 120);
        ObjectNode branchLine = optionProperties.putObject("branchLine");
        branchLine.put("type", "string");
        branchLine.put("minLength", 1);
        branchLine.put("maxLength", 60);
        branchLine.put(
                "description",
                "이 선택지를 고른 직후 들려줄 한 문장 대사 - family.meaning()을 벗어나지 않는 선에서 아이 발화에 맞게 직접 쓴다.");
        ObjectNode optionFamilyId = optionProperties.putObject("actionFamilyId");
        optionFamilyId.put("type", "string");
        ArrayNode optionFamilyEnum = optionFamilyId.putArray("enum");
        actionFamilyIds.forEach(optionFamilyEnum::add);
        ArrayNode optionRequired = optionItems.putArray("required");
        optionRequired.add("id").add("label").add("meaning").add("branchLine").add("actionFamilyId");
        optionItems.put("additionalProperties", false);

        ArrayNode required = schema.putArray("required");
        required.add("route").add("childRelevantMeaning").add("coverageStatus").add("coverageReason")
                .add("responseText").add("speakerId").add("actionFamilyId").add("rejoinAnchorId")
                .add("fallbackFamilyId").add("options");
        schema.put("additionalProperties", false);
        return schema;
    }

    private void addStringProperty(ObjectNode properties, String name, int minLength, int maxLength) {
        ObjectNode node = properties.putObject(name);
        node.put("type", "string");
        node.put("minLength", minLength);
        node.put("maxLength", maxLength);
    }

    private void addNullableFamilyEnum(ObjectNode properties, String name, List<String> actionFamilyIds, String description) {
        ObjectNode node = properties.putObject(name);
        ArrayNode type = node.putArray("type");
        type.add("string").add("null");
        ArrayNode enumNode = node.putArray("enum");
        actionFamilyIds.forEach(enumNode::add);
        enumNode.addNull();
        node.put("description", description);
    }

    /**
     * 정책 텍스트 자체는 이 버전이 가리키는 route_prompt 행에 들어 있다(fe/content/prompts에 작성됨).
     * 여기서는 버전 문구만 조합하는데, 이는 요청 시점에 모델이 어떤 정책을 따라야 하는지를 다시 명시하기 위함이다.
     */
    private String systemPrompt(String promptVersion) {
        return "정책 버전은 " + promptVersion + "이다. "
                + routePromptService.requirePrompt(promptVersion).systemText();
    }
}
