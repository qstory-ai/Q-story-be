package com.qstory.backend.provider.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.choicecopy.ChoiceCopyService;
import com.qstory.backend.config.AppProperties;
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

/** Java port of providers/openrouter.mjs: chat/completions route planning + audio/speech TTS (buffered and streamed). */
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
    private final String apiKey;
    private final String llmModel;
    private final String ttsModel;
    private final String ttsVoice;

    public OpenRouterClient(
            HttpClient httpClient, ObjectMapper objectMapper, RouteResultValidator routeResultValidator,
            ChoiceCopyService choiceCopyService, AppProperties config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.routeResultValidator = routeResultValidator;
        this.choiceCopyService = choiceCopyService;
        this.apiKey = config.providers().openRouter().apiKey();
        this.llmModel = config.providers().openRouter().llmModel();
        this.ttsModel = config.providers().openRouter().ttsModel();
        this.ttsVoice = config.providers().openRouter().ttsVoice();
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
            return "THREE_PATHS".equals(agencyAware.route())
                    ? agencyAware.withOptions(choiceCopyService.authoredChoiceOptions(
                            agencyAware.options(), request.transcript(), request.questionRound()))
                    : agencyAware;
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.", true, error);
        }
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
        payload.put("instruction",
                "아이에게 들려줄 responseText와 실제 발화의 핵심인 childRelevantMeaning을 작성하라. "
                        + "아이 말과 실제 처리 경로의 일치도를 coverageStatus와 coverageReason으로 작성하라. "
                        + "행동·변화 route는 허용 family 하나를, THREE_PATHS는 서로 다른 허용 family 세 개를 사용하라.");
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
        coverageEnum.add("exact").add("partial").add("uncovered");
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
        ObjectNode optionFamilyId = optionProperties.putObject("actionFamilyId");
        optionFamilyId.put("type", "string");
        ArrayNode optionFamilyEnum = optionFamilyId.putArray("enum");
        actionFamilyIds.forEach(optionFamilyEnum::add);
        ArrayNode optionRequired = optionItems.putArray("required");
        optionRequired.add("id").add("label").add("meaning").add("actionFamilyId");
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

    private String systemPrompt(String promptVersion) {
        return String.join(" ",
                "정책 버전은 " + promptVersion + "이다.",
                "너는 6~9세 아이와 함께 읽는 한국어 동화의 안전한 질문 라우터다.",
                "아이의 실제 발화 종류와 뜻을 바꾸거나 아이가 하지 않은 질문을 지어내지 않는다.",
                "다음 우선순위에서 처음 만족하는 route 하나만 고른다.",
                "1 DIRECT_ACTION: 원래 발화가 안전하고, 자리를 떠났다가 돌아오는 과정 없이 현재 위치에서 한 가지 행동을 바로 실행하자는 구체적 제안.",
                "2 ANSWER_RESUME: 왜·무엇·감정 같은 사실 질문에 1~2문장 답이면 충분하고, 아이의 다음 행동을 고를 필요가 없는 질문.",
                "3 THREE_PATHS: 아이가 방법·선택·어떻게 할지를 고민하거나, 무섭다·걱정된다·믿어도 될까·괜찮을까처럼 현재 장면의 안전을 걱정해 다음 행동을 고르는 것이 도움이 되며 서로 다른 세 허용 행동이 실제로 모두 의미 있을 때.",
                "4 SCENE_REPLACE: 고정 사건의 기능은 유지하면서 과정을 바꾸는 명확한 제안.",
                "5 DETOUR_REJOIN: 잠깐·먼저·전에 조사하거나 물러난 뒤 다시·돌아오자고 명시한 제안. 이런 왕복 표지가 있으면 DIRECT_ACTION보다 DETOUR_REJOIN이다.",
                "6 CLARIFY_ONCE: 행동·질문 의도는 있지만 지시어 또는 핵심 단어 하나가 빠져 쉬운 확인 질문 한 번으로 복구할 수 있는 발화.",
                "7 GENTLE_REDIRECT: 원래 발화가 위험·폭력·이야기 밖 요청·금지된 미래 정보 요구인 경우. 안전한 대안을 제시할 수 있어도 route는 바꾸지 않는다.",
                "8 SKIP_CONTINUE: 응·그래 같은 단순 동의, 몰라·안 할래 같은 거부, 감탄·말잇기·같은 낱말 반복·깨진 전사처럼 복구 가능한 행동 의도가 없는 발화.",
                "clarificationAlreadyUsed가 true이면 CLARIFY_ONCE를 고르지 말고 안전하게 판단할 수 없으면 SKIP_CONTINUE를 고른다.",
                "위험한 원래 발화를 안전한 행동 family로 바꾸어 DIRECT_ACTION·SCENE_REPLACE로 분류하지 않는다.",
                "coverageStatus는 아이의 의도와 실제로 실행하거나 답할 경로의 일치도를 판정한다.",
                "exact는 질문에 직접 답할 수 있거나 아이가 말한 행동과 허용 family가 거의 같은 경우다.",
                "partial은 가장 가까운 허용 family가 핵심 의도 일부만 반영하고 대상·방식·결과가 달라지는 경우다.",
                "uncovered는 아이가 제안한 안전한 행동을 이 장면의 허용 family로 실행할 수 없는 경우다. 원래 발화가 위험해서 거절하는 경우와 구분한다.",
                "coverageReason은 원문을 복제하지 말고 일치하거나 빠진 핵심을 한 문장 의미 요약으로 작성한다.",
                "단순 동의·거부·잡음에 다시 선택을 묻기 위해 CLARIFY_ONCE를 사용하지 않는다.",
                "DIRECT_ACTION을 다시 세 선택지로 돌리지 않는다. 사실 질문에는 억지 선택지를 만들지 않는다.",
                "THREE_PATHS만 정확히 세 option을 만들고, option은 서로 다른 허용 action family를 사용한다.",
                "THREE_PATHS의 세 선택지는 관찰·대화·행동처럼 서로 다른 놀이 감각과 즉시 상상할 수 있는 결과를 가져야 한다. 동의어 선택지를 만들지 않는다.",
                "THREE_PATHS의 label과 meaning은 childTranscript가 드러낸 걱정·궁금증·제안에 직접 답해야 한다. 아이가 언급하지 않은 소품을 갑자기 핵심 방법처럼 끼워 넣지 않는다.",
                "responseText는 현재 화자인 그레텔이 또래 아이에게 직접 말하는 친근한 한국어 반말로 쓰며 요·습니다·저는 같은 존댓말을 섞지 않는다.",
                "ANSWER_RESUME은 현재 장면에서 관찰 가능한 정보만 답하고 forbiddenKnowledge를 사실·추측·가능성 형태로도 언급하지 않는다.",
                "SKIP_CONTINUE는 새 질문이나 선택지를 다시 제시하지 말고 \"괜찮아, 이야기를 계속 들어보자.\"처럼 한 문장으로 자연스럽게 재개한다.",
                "CLARIFY_ONCE만 쉬운 확인 질문 한 문장을 쓴다.",
                "GENTLE_REDIRECT는 위험을 짧게 막고 허용된 안전 행동 하나만 제안하며, 거절한 위험 행동을 대안으로 다시 표현하지 않는다.",
                "THREE_PATHS의 실제 선택지는 options에 있으므로 responseText에는 짧은 선택 초대만 써도 된다.",
                "필드 규칙은 선택 사항이 아니라 서버 검증 규칙이다.",
                "ANSWER_RESUME·CLARIFY_ONCE·GENTLE_REDIRECT·SKIP_CONTINUE는 actionFamilyId, rejoinAnchorId, fallbackFamilyId를 모두 null로 하고 options는 []로 한다.",
                "DIRECT_ACTION·SCENE_REPLACE·DETOUR_REJOIN은 허용 actionFamilyId 하나, 제공된 fixedRejoinAnchorId, fallbackFamilyId를 쓰고 options는 []로 한다.",
                "THREE_PATHS는 actionFamilyId를 null로 하고 제공된 fixedRejoinAnchorId와 fallbackFamilyId를 쓰며 options를 정확히 3개 만든다.",
                "현재 장면의 허용 family·화자·합류점만 사용하고 반전·미래 사건·결말을 선공개하지 않는다.",
                "응답을 반환하기 전에 한국어 맞춤법·띄어쓰기·오탈자와 말투 일치를 한 번 확인한다.",
                "공포·폭력·위험 행동을 구체적으로 설명하지 않고, 맞춤법과 띄어쓰기를 확인한다.",
                "사용하지 않는 nullable 필드는 null, options는 빈 배열로 반환한다.");
    }
}
