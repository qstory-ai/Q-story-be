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
    private final RoutePromptService routePromptService;
    private final String apiKey;
    private final String llmModel;
    private final String ttsModel;
    private final String ttsVoice;

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

    /**
     * The policy text itself lives in the route_prompt row this version names (authored in
     * fe/content/prompts). Only the version line is composed here, because it restates at request
     * time which policy the model is being held to.
     */
    private String systemPrompt(String promptVersion) {
        return "정책 버전은 " + promptVersion + "이다. "
                + routePromptService.requirePrompt(promptVersion).systemText();
    }
}
