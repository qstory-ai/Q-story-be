package com.qstory.backend.provider.openrouter.util;
import com.qstory.backend.provider.openrouter.SynthesizedAudioStream;
import com.qstory.backend.provider.openrouter.SynthesizedAudio;
import com.qstory.backend.provider.openrouter.ContentGeneration;
import com.qstory.backend.provider.openrouter.FewShotExample;
import com.qstory.backend.provider.openrouter.RouteClassification;
import com.qstory.backend.provider.openrouter.SafetyVerdict;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.enums.RoutePromptStageKind;
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

    /** stage3(content_generator)의 옵션 개수 재시도 - JSON 스키마의 minItems/maxItems를 optionSlots에
     * 맞춰 만들어도 모델이 개수를 틀리게 반환할 수 있어(계획 문서가 명시한 한계), 검증 실패 시 피드백을
     * 담아 한 번 더 시도한다(ShadowFamilyGenerationService.generateDraft/LiveBranchExecutionWorker.run과
     * 같은 재시도-피드백 패턴). */
    private static final int CONTENT_MAX_ATTEMPTS = 2;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RouteResultValidator routeResultValidator;
    private final RoutePromptService routePromptService;
    private final String apiKey;
    private final String llmModel;
    private final String safetyModel;
    private final String ttsModel;
    private final String ttsVoice;
    private final String imageModel;

    public OpenRouterClient(
            HttpClient httpClient, ObjectMapper objectMapper, RouteResultValidator routeResultValidator,
            RoutePromptService routePromptService, AppProperties config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.routeResultValidator = routeResultValidator;
        this.routePromptService = routePromptService;
        this.apiKey = config.providers().openRouter().apiKey();
        this.llmModel = config.providers().openRouter().llmModel();
        this.safetyModel = config.providers().openRouter().safetyModel();
        this.ttsModel = config.providers().openRouter().ttsModel();
        this.ttsVoice = config.providers().openRouter().ttsVoice();
        this.imageModel = config.providers().openRouter().imageModel();
    }

    // ---------------------------------------------------------------------------------------
    // Phase 2: 3단계 라우팅 파이프라인 (safety_scope_gate -> route_classifier -> content_generator)
    // 예전의 단일 호출 generatePlan()/routeSchema()를 대체한다. 오케스트레이션(REDIRECT/NEW_CHOICES
    // 분기, guaranteeBetaAgencyChoice/sanitizeGeneratedOptionCopy 적용)은
    // question.service.QuestionRoutingService가 맡는다 - 이 클래스는 각 단계의 프롬프트 조립 +
    // 요청/검증만 책임진다.
    // ---------------------------------------------------------------------------------------

    public record SafetyGateRequest(String transcript, StoryContext storyContext) {}

    /** 1단계: 안전/범위만 판정한다(route는 절대 고르지 않는다) - 실패 시 예외를 던진다(재시도는 상위에서). */
    public SafetyVerdict evaluateSafety(SafetyGateRequest request, RequestDeadline deadline) {
        StoryContext ctx = request.storyContext();
        RoutePromptService.StagePrompt stagePrompt =
                routePromptService.requireStage(ctx.versions().promptVersion(), RoutePromptStageKind.SAFETY);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("childTranscript", request.transcript());
        ArrayNode forbiddenKnowledge = payload.putArray("forbiddenKnowledge");
        ctx.forbiddenKnowledge().forEach(forbiddenKnowledge::add);

        JsonNode raw = generateStructuredCompletion(
                safetyModel, stagePrompt.systemText(), stagePrompt.examples(), payload.toString(),
                safetySchema(), "qstory_safety_gate_v1", 300, 0,
                ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "안전 확인을 하지 못했어요.", deadline);
        SafetyVerdict verdict = routeResultValidator.validateSafetyVerdict(raw, safetyModel);
        if (verdict == null) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "안전 확인 결과를 확인하지 못했어요.");
        }
        return verdict;
    }

    private ObjectNode safetySchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode verdict = properties.putObject("verdict");
        verdict.put("type", "string");
        verdict.putArray("enum").add("PASS").add("REDIRECT");
        ObjectNode redirectReason = properties.putObject("redirectReason");
        redirectReason.putArray("type").add("string").add("null");
        redirectReason.put("description", "REDIRECT일 때만 위험/이야기 밖/금지지식 요구 중 어떤 것인지 한 문장으로. PASS면 null.");
        ObjectNode responseText = properties.putObject("responseText");
        responseText.putArray("type").add("string").add("null");
        responseText.put("description", "REDIRECT일 때만 - 위험을 짧게 막고 안전한 대안 하나만 제시. PASS면 null.");
        ArrayNode required = schema.putArray("required");
        required.add("verdict").add("redirectReason").add("responseText");
        schema.put("additionalProperties", false);
        return schema;
    }

    public record ClassifyRequest(String transcript, StoryContext storyContext, int questionRound) {}

    /** 2단계: PASS된 발화만 받는다 - 안전을 다시 판정하지 않고 route/coverage만 정한다. */
    public RouteClassification classifyRoute(ClassifyRequest request, RequestDeadline deadline) {
        StoryContext ctx = request.storyContext();
        RoutePromptService.StagePrompt stagePrompt =
                routePromptService.requireStage(ctx.versions().promptVersion(), RoutePromptStageKind.CLASSIFIER);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("childTranscript", request.transcript());
        payload.put("clarificationAlreadyUsed", request.questionRound() > 1);
        ArrayNode allowedFamilies = payload.putArray("allowedFamilies");
        for (ActionFamily family : ctx.actionFamilies()) {
            ObjectNode node = allowedFamilies.addObject();
            node.put("id", family.id());
            node.put("summary", family.meaning());
        }
        payload.put("hasRejoinAnchor", ctx.rejoinAt() != null);
        // sceneObservables/answerableFacts를 위한 전용 저작 필드가 아직 route-context.yaml에 없다
        // (컨텐츠 빌드 파이프라인은 이 백엔드 작업 범위 밖) - 현재로선 currentScene 요약을 최선의
        // 대체값으로 함께 쓴다. 콘텐츠 팀이 두 필드를 별도로 저작하게 되면 여기를 교체해야 한다.
        ArrayNode sceneObservables = payload.putArray("sceneObservables");
        sceneObservables.add(ctx.summary());
        ArrayNode answerableFacts = payload.putArray("answerableFacts");
        answerableFacts.add(ctx.summary());
        ArrayNode allowedSpeakerIds = payload.putArray("allowedSpeakerIds");
        ctx.allowedSpeakerIds().forEach(allowedSpeakerIds::add);
        payload.put("fixedRejoinAnchorId", ctx.rejoinAt());
        payload.put("fallbackFamilyId", ctx.fallbackFamilyId());

        JsonNode raw = generateStructuredCompletion(
                llmModel, stagePrompt.systemText(), stagePrompt.examples(), payload.toString(),
                classificationSchema(ctx.actionFamilyIds(), ctx.allowedSpeakerIds()), "qstory_route_classifier_v1",
                700, 0, ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "질문 방향을 정하지 못했어요.", deadline);
        RouteClassification classification = routeResultValidator.validateClassification(raw, ctx, llmModel);
        if (classification == null) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "질문 방향을 확인하지 못했어요.");
        }
        return classification;
    }

    private ObjectNode classificationSchema(List<String> actionFamilyIds, List<String> allowedSpeakerIds) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode route = properties.putObject("route");
        route.put("type", "string");
        ArrayNode routeEnum = route.putArray("enum");
        com.qstory.backend.common.enums.RouteKind.CLASSIFIER_ROUTES.forEach(routeEnum::add);

        ObjectNode matchedGate = properties.putObject("matchedGate");
        matchedGate.put("type", "string");
        matchedGate.putArray("enum").add("G1").add("G2").add("G3").add("G4").add("G5").add("G6").add("G7").add("NEW");
        matchedGate.put("description", "실제로 만족시킨 게이트. NEW_CHOICES를 골랐을 때만 NEW.");

        ObjectNode coverageStatus = properties.putObject("coverageStatus");
        coverageStatus.put("type", "string");
        ArrayNode coverageEnum = coverageStatus.putArray("enum");
        com.qstory.backend.common.enums.CoverageStatus.WIRE_VALUES.forEach(coverageEnum::add);

        addStringProperty(properties, "coverageReason", 1, 160);
        addStringProperty(properties, "childRelevantMeaning", 1, 160);

        ObjectNode speakerId = properties.putObject("speakerId");
        speakerId.put("type", "string");
        ArrayNode speakerEnum = speakerId.putArray("enum");
        allowedSpeakerIds.forEach(speakerEnum::add);

        addNullableFamilyEnum(properties, "actionFamilyId", actionFamilyIds,
                "행동·장면변화·우회 route만 허용 family 하나. 단순 route/THREE_PATHS/NEW_CHOICES는 반드시 null.");
        ObjectNode rejoinAnchorId = properties.putObject("rejoinAnchorId");
        rejoinAnchorId.putArray("type").add("string").add("null");
        rejoinAnchorId.put("description", "행동·장면변화·우회·THREE_PATHS는 제공된 fixedRejoinAnchorId. 단순 route/NEW_CHOICES는 반드시 null.");
        addNullableFamilyEnum(properties, "fallbackFamilyId", actionFamilyIds,
                "행동·장면변화·우회·THREE_PATHS는 제공된 fallbackFamilyId. 단순 route/NEW_CHOICES는 반드시 null.");

        ArrayNode required = schema.putArray("required");
        required.add("route").add("matchedGate").add("coverageStatus").add("coverageReason")
                .add("childRelevantMeaning").add("speakerId").add("actionFamilyId").add("rejoinAnchorId")
                .add("fallbackFamilyId");
        schema.put("additionalProperties", false);
        return schema;
    }

    public record ContentRequest(
            String route, String childRelevantMeaning, String coverageStatus, String speakerId,
            StoryContext storyContext, int optionSlots) {}

    /** 3단계: 이미 확정된 route를 그대로 신뢰하고 다시 분류하지 않는다 - responseText/options만 작성한다. */
    public ContentGeneration generateContent(ContentRequest request, RequestDeadline deadline) {
        StoryContext ctx = request.storyContext();
        RoutePromptService.StagePrompt stagePrompt =
                routePromptService.requireStage(ctx.versions().promptVersion(), RoutePromptStageKind.GENERATOR);
        List<String> revisionFeedback = List.of();
        for (int attempt = 1; attempt <= CONTENT_MAX_ATTEMPTS; attempt++) {
            JsonNode raw = generateStructuredCompletion(
                    llmModel, stagePrompt.systemText(), stagePrompt.examples(),
                    contentUserPayload(request, revisionFeedback).toString(),
                    contentSchema(ctx.actionFamilyIds(), request.optionSlots()), "qstory_content_generator_v1", 900,
                    0.35, ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 만들지 못했어요.", deadline);
            ContentGeneration content =
                    routeResultValidator.validateContent(raw, ctx, request.route(), request.optionSlots());
            if (content != null) {
                return content;
            }
            revisionFeedback = List.of(
                    "options 배열은 정확히 " + request.optionSlots() + "개여야 하고, 각 actionFamilyId는 서로 달라야 한다.");
        }
        throw new ProviderException(
                ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.");
    }

    private ObjectNode contentUserPayload(ContentRequest request, List<String> revisionFeedback) {
        StoryContext ctx = request.storyContext();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("route", request.route());
        payload.put("childRelevantMeaning", request.childRelevantMeaning());
        payload.put("coverageStatus", request.coverageStatus());
        payload.put("speaker", request.speakerId());
        ArrayNode sceneObservables = payload.putArray("sceneObservables");
        sceneObservables.add(ctx.summary());
        ArrayNode forbiddenKnowledge = payload.putArray("forbiddenKnowledge");
        ctx.forbiddenKnowledge().forEach(forbiddenKnowledge::add);
        ArrayNode allowedFamilies = payload.putArray("allowedFamilies");
        for (ActionFamily family : ctx.actionFamilies()) {
            ObjectNode node = allowedFamilies.addObject();
            node.put("id", family.id());
            node.put("summary", family.meaning());
        }
        payload.put("optionSlots", request.optionSlots());
        if (!revisionFeedback.isEmpty()) {
            ArrayNode feedback = payload.putArray("revisionFeedback");
            revisionFeedback.forEach(feedback::add);
        }
        return payload;
    }

    private ObjectNode contentSchema(List<String> actionFamilyIds, int optionSlots) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        addStringProperty(properties, "responseText", 1, 200);

        ObjectNode options = properties.putObject("options");
        options.put("type", "array");
        options.put("minItems", optionSlots);
        options.put("maxItems", optionSlots);
        options.put("description", optionSlots == 3
                ? "정확히 3개, 서로 다른 allowedFamilies를 하나씩 사용한다."
                : "이 route는 선택지가 없으므로 반드시 빈 배열이다.");
        ObjectNode optionItems = options.putObject("items");
        optionItems.put("type", "object");
        ObjectNode optionProperties = optionItems.putObject("properties");
        ObjectNode optionId = optionProperties.putObject("id");
        optionId.put("type", "string");
        optionId.putArray("enum").add("OPTION_1").add("OPTION_2").add("OPTION_3");
        addStringProperty(optionProperties, "label", 1, 18);
        addStringProperty(optionProperties, "meaning", 1, 120);
        ObjectNode branchLine = optionProperties.putObject("branchLine");
        branchLine.put("type", "string");
        branchLine.put("minLength", 1);
        branchLine.put("maxLength", 60);
        branchLine.put(
                "description",
                "이 선택지를 고른 직후 들려줄 한 문장 대사 - family의 의미를 벗어나지 않는 선에서 아이 발화에 맞게 직접 쓴다.");
        ObjectNode optionFamilyId = optionProperties.putObject("actionFamilyId");
        optionFamilyId.put("type", "string");
        ArrayNode optionFamilyEnum = optionFamilyId.putArray("enum");
        actionFamilyIds.forEach(optionFamilyEnum::add);
        optionItems.putArray("required").add("id").add("label").add("meaning").add("branchLine").add("actionFamilyId");
        optionItems.put("additionalProperties", false);

        schema.putArray("required").add("responseText").add("options");
        schema.put("additionalProperties", false);
        return schema;
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
     * 앵커에 독립적인 companion-chat 표면을 위한 형제 메서드: LLM 호출은 한 번뿐이며,
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
    private static final String NOT_FOUND = " ";

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
            HttpRequest httpRequest = buildSpeechHttpRequest(text, voice, speed, deadline);
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
            boolean isPcm = isPcmContentType(responseMimeType);
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
            HttpRequest httpRequest = buildSpeechHttpRequest(text, voice, speed, deadline);
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_TTS_FAILED, "답변 음성을 만들지 못했어요.", response.statusCode() >= 429);
            }
            String responseMimeType = response.headers().firstValue("content-type").orElse("audio/pcm");
            boolean isPcm = isPcmContentType(responseMimeType);
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
     * generateCompanionReply()는 자신의 고정된 스키마(CompanionReply)에 강하게 결합돼 있어 재사용하기
     * 어렵다. shadow-family 생성, live-branch 생성, 그리고 Phase 2의 3단계 라우팅 파이프라인처럼
     * 서로 다른 JSON 스키마를 쓰는 여러 호출자를 위해, 요청 조립·에러 처리 배관(plumbing)만
     * 일반화한 것이다 - 스키마 자체는 호출자가 만든다.
     *
     * <p>few-shot 예시(examples)는 system 메시지 다음, 실제 user 메시지 앞에 user/assistant 메시지
     * 쌍으로 삽입된다 - 이 코드베이스에 멀티턴 프롬프팅 선례가 이전에 없었다(Phase 2에서 처음 도입).
     */
    public JsonNode generateStructuredCompletion(
            String model, String systemPrompt, List<FewShotExample> examples, String userPayloadJson,
            ObjectNode schema, String schemaName, int maxTokens, double temperature,
            ProviderErrorCode failureCode, String failureSafeDetail, RequestDeadline deadline) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            ArrayNode messages = root.putArray("messages");
            ObjectNode systemMessage = messages.addObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            for (FewShotExample example : examples) {
                ObjectNode exampleUser = messages.addObject();
                exampleUser.put("role", "user");
                exampleUser.put("content", example.input());
                ObjectNode exampleAssistant = messages.addObject();
                exampleAssistant.put("role", "assistant");
                exampleAssistant.put("content", example.output());
            }
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
            root.put("temperature", temperature);
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
        } catch (JsonProcessingException malformed) {
            // 응답 파싱 실패는 네트워크 장애가 아니라 스키마/응답 형식 버그일 가능성이 높다 - 무조건
            // retryable=true로 두면 이런 버그가 재시도 뒤에 숨어버린다(CONTENT_MAX_ATTEMPTS만큼
            // 조용히 반복되다 결국 같은 실패로 끝난다).
            throw new ProviderException(failureCode, failureSafeDetail, false, malformed);
        } catch (Exception error) {
            throw new ProviderException(failureCode, failureSafeDetail, true, error);
        }
    }

    /** 예전 단일 모델/단일 턴/temperature=0 호출자(ShadowFamilyGenerationService, LiveBranchExecutionWorker)를 위한 하위 호환 오버로드. */
    public JsonNode generateStructuredCompletion(
            String systemPrompt, String userPayloadJson, ObjectNode schema, String schemaName, int maxTokens,
            ProviderErrorCode failureCode, String failureSafeDetail, RequestDeadline deadline) {
        return generateStructuredCompletion(
                llmModel, systemPrompt, List.of(), userPayloadJson, schema, schemaName, maxTokens, 0,
                failureCode, failureSafeDetail, deadline);
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
            // base64 인코딩된 길이만으로 디코딩된 크기를 미리 추정해서 검사한다 - 그래야 과도하게
            // 큰(또는 악의적인) 응답이 실제로 10MB 제한을 초과하는지 확인하겠다고 전체를 먼저
            // 디코딩해 메모리를 할당하는 낭비를 피할 수 있다.
            long estimatedDecodedBytes = (encoded.length() / 4L) * 3;
            if (estimatedDecodedBytes > 10 * 1024 * 1024) {
                throw new ProviderException(ProviderErrorCode.OPENROUTER_IMAGE_INVALID, "삽화 형식을 확인하지 못했어요.");
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

    /** synthesize()/synthesizeStream()이 공유하는 /audio/speech 요청 조립. */
    private HttpRequest buildSpeechHttpRequest(String text, String voice, double speed, RequestDeadline deadline) {
        return deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/audio/speech"))
                        .headers(baseHeaders())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(speechRequestBody(text, voice, speed))))
                .build();
    }

    /** synthesize()/synthesizeStream()이 공유하는, 응답 content-type으로부터 raw PCM 여부를 판별하는 로직. */
    private boolean isPcmContentType(String contentType) {
        return contentType.contains("pcm") || contentType.contains("L16")
                || contentType.equals("application/octet-stream");
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
}
