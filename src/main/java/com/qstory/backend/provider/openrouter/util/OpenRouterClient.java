package com.qstory.backend.provider.openrouter.util;
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
import com.qstory.backend.story.ActionFamily;
import com.qstory.backend.story.StoryContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import com.qstory.backend.story.CompanionPersona;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** OpenRouter 클라이언트: chat/completions(라우팅 3단계·컴패니언 챗·구조화 생성) + 이미지 생성.
 * TTS는 GeminiTtsClient가 맡는다. */
@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);
    private static final String BASE_URL = "https://openrouter.ai/api/v1";
    /** 진단 로그에 남길 실패 응답 본문의 최대 길이 - 전체를 남기면 로그가 지나치게 커질 수 있다. */
    private static final int FAILURE_BODY_LOG_LIMIT = 500;

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
        this.imageModel = config.providers().openRouter().imageModel();
    }

    // ---------------------------------------------------------------------------------------
    // 3단계 라우팅 파이프라인 (safety_scope_gate -> route_classifier -> content_generator)
    // 오케스트레이션(REDIRECT/NEW_CHOICES 분기, guaranteeBetaAgencyChoice/sanitizeGeneratedOptionCopy 적용)은
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
            String storyTitle,
            String primarySpeakerId,
            List<String> allowedSpeakerIds,
            List<String> forbiddenKnowledge,
            /** story_persona(personas.yaml)에서 온 primarySpeakerId의 시트. 임포트 전이면 null. */
            CompanionPersona persona,
            /** 대화 기록·장면·실행한 행동·정리 신호(Q-31). */
            com.qstory.backend.companionchat.DialogueInput dialogue,
            /** 질문 초대 중이면 그 앵커 - 실행 가능한 행동 제안을 고를 수 있는 범위. 상시 대화면 null. */
            com.qstory.backend.story.Anchor inviteAnchor) {

        public CompanionRequest {
            dialogue = dialogue == null ? com.qstory.backend.companionchat.DialogueInput.empty() : dialogue;
        }

        List<String> inviteFamilyIds() {
            return inviteAnchor == null ? List.of() : inviteAnchor.actionFamilies().stream().map(ActionFamily::id).toList();
        }
    }

    /**
     * @param replyKind              ANSWER(물은 것에 답함) / EMPATHY(감정·경험을 받아줌) / WAIT(공감하고 기다림)
     *                               / CLOSE(마무리 인사) / REDIRECT(안전 규칙으로 돌림)
     * @param childWantsToEnd        아이가 그만 이야기하고 싶다고 분명히 말했는지
     * @param childMeaning           아이 말의 뜻을 짧게 - 리포트·기록용, 확대 해석하지 않는다
     * @param asksForHelp            "모르겠어"·"도와줘"처럼 도움을 바라는지
     * @param proposedActionFamilyId 질문 초대에서 아이가 실행 가능한 행동을 제안했으면 그 family id(뜻 확인 전)
     */
    public record CompanionReply(
            String interactionMode, String responseText, String speakerId,
            String topicTag, String toneTag, String valueTag,
            String replyKind, boolean childWantsToEnd, String childMeaning, boolean asksForHelp,
            String proposedActionFamilyId) {}

    static final List<String> REPLY_KINDS = List.of("ANSWER", "EMPATHY", "WAIT", "CLOSE", "REDIRECT");

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
                logProviderHttpFailure("generateCompanionReply", response.statusCode(), response.body());
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

    CompanionReply validateCompanionReply(JsonNode value, CompanionRequest request) {
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
        String replyKind = value.path("replyKind").asText("").trim();
        if (!REPLY_KINDS.contains(replyKind)) {
            return null;
        }
        String childMeaning = value.path("childMeaning").isTextual() ? value.get("childMeaning").asText().trim() : "";
        if (childMeaning.length() > 80) {
            childMeaning = childMeaning.substring(0, 80);
        }
        // 질문 초대가 아닐 때, 또는 앵커에 없는 행동이면 제안으로 받지 않는다 - 준비되지 않은 행동을
        // 실행했다고 꾸미지 않기 위해서다.
        JsonNode proposedNode = value.get("proposedActionFamilyId");
        String proposed = proposedNode == null || proposedNode.isNull() ? null : proposedNode.asText("").trim();
        if (proposed != null && !request.inviteFamilyIds().contains(proposed)) {
            proposed = null;
        }
        return new CompanionReply(
                interactionMode, routeResultValidator.normalizeKoreanResponseText(responseText), speakerId,
                topicTag, toneTag, valueTag,
                replyKind, value.path("childWantsToEnd").asBoolean(false), childMeaning,
                value.path("asksForHelp").asBoolean(false), proposed);
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
        systemMessage.put("content", companionSystemPrompt(request));

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", companionUserPayload(request).toString());

        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", "q_story_companion_v2");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", companionSchema(request.allowedSpeakerIds(), request.inviteFamilyIds()));

        root.putObject("provider").put("require_parameters", true);
        ObjectNode reasoning = root.putObject("reasoning");
        reasoning.put("effort", "minimal");
        reasoning.put("exclude", true);
        root.put("temperature", 0);
        root.put("max_tokens", 600);
        return root.toString();
    }

    ObjectNode companionUserPayload(CompanionRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("childMessage", request.transcript());
        payload.put("primarySpeakerId", request.primarySpeakerId());
        ArrayNode allowedSpeakerIds = payload.putArray("allowedSpeakerIds");
        request.allowedSpeakerIds().forEach(allowedSpeakerIds::add);
        ArrayNode forbiddenKnowledge = payload.putArray("forbiddenKnowledge");
        request.forbiddenKnowledge().forEach(forbiddenKnowledge::add);
        if (request.inviteAnchor() != null) {
            request.inviteAnchor().forbiddenKnowledge().forEach(forbiddenKnowledge::add);
        }

        var dialogue = request.dialogue();
        ArrayNode history = payload.putArray("conversationSoFar");
        for (var turn : dialogue.history()) {
            ObjectNode node = history.addObject();
            node.put("who", "CHILD".equals(turn.role()) ? "child" : "you");
            node.put("text", turn.text());
        }
        if (dialogue.scene() != null) {
            ObjectNode scene = payload.putObject("currentScene");
            scene.put("title", dialogue.scene().title());
            ArrayNode storySoFar = scene.putArray("storySoFar");
            dialogue.scene().storySoFar().forEach(storySoFar::add);
            ArrayNode recentLines = scene.putArray("linesJustHeard");
            dialogue.scene().recentLines().forEach(recentLines::add);
            scene.put("visibleInPicture", dialogue.scene().visual());
        }
        ArrayNode executed = payload.putArray("actionsAlreadyTaken");
        dialogue.executedActions().forEach(executed::add);
        if (request.inviteAnchor() != null) {
            ObjectNode invite = payload.putObject("questionInvite");
            invite.put("situation", request.inviteAnchor().summary());
            ArrayNode actions = invite.putArray("preparedActions");
            for (ActionFamily family : request.inviteAnchor().actionFamilies()) {
                ObjectNode node = actions.addObject();
                node.put("id", family.id());
                node.put("meaning", family.meaning());
            }
        }
        payload.put("wrapUp", dialogue.wrapUp());
        return payload;
    }

    private ObjectNode companionSchema(List<String> allowedSpeakerIds, List<String> inviteFamilyIds) {
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

        ObjectNode replyKind = properties.putObject("replyKind");
        replyKind.put("type", "string");
        ArrayNode replyKindEnum = replyKind.putArray("enum");
        REPLY_KINDS.forEach(replyKindEnum::add);
        properties.putObject("childWantsToEnd").put("type", "boolean");
        addStringProperty(properties, "childMeaning", 0, 80);
        properties.putObject("asksForHelp").put("type", "boolean");
        // 질문 초대가 아니면 null만 허용한다 - 상시 대화는 이야기 진행을 바꾸지 않는다.
        addNullableLabelEnum(properties, "proposedActionFamilyId", inviteFamilyIds);

        ArrayNode required = schema.putArray("required");
        required.add("interactionMode").add("responseText").add("speakerId")
                .add("topicTag").add("toneTag").add("valueTag")
                .add("replyKind").add("childWantsToEnd").add("childMeaning").add("asksForHelp")
                .add("proposedActionFamilyId");
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
     * 컴패니언 챗 시스템 프롬프트. 캐릭터 성격은 여기 Java 문자열이 아니라 story_persona 테이블
     * (fe personas.yaml → POST /v1/admin/stories/import → CompanionPersonaRegistry)에서 온
     * {@link CompanionPersona}로 채운다 - 페르소나는 personas.yaml 한 곳에서만 관리된다. 라우팅 시스템 프롬프트(route_prompt)와는 안전 규칙 블록만
     * 공유한다(라우팅 의사결정 트리가 아니기 때문).
     *
     * <p>페르소나 시트의 지식 경계(아는 것/모르는 것/먼저 말하지 않는 것)를 그대로 지시로 옮긴다 -
     * 상시 대화가 결말·반전을 미리 말하지 않게 하는 근거다. 이야기 세계관 안에서 반응하고, 대화가
     * 이어지도록 자연스러운 순간에 짧은 되질문을 던지게 한다 - 규칙이 아니라 성향이다.
     */
    String companionSystemPrompt(CompanionRequest request) {
        String safetyFragment = routePromptService.requirePrompt(request.promptVersion()).companionSafetyFragment();
        String storyTitle = request.storyTitle() == null || request.storyTitle().isBlank()
                ? "이 동화" : "'" + request.storyTitle() + "'";
        List<String> lines = new ArrayList<>();
        lines.add("너는 6~9세 아이와 한국어 동화 " + storyTitle + " 속 등장인물로서 대화하는 친구다.");
        lines.add("너는 primarySpeakerId가 가리키는 등장인물이며, 이 인물의 시점과 말투를 끝까지 유지한다.");
        lines.addAll(personaLines(request.persona()));
        lines.add("대화하는 아이는 이야기 밖에서 함께 보고 있는 친구다. 아이를 오빠·언니·헨젤처럼 이야기 속 인물로 부르지 않는다.");
        // 맥락: 지금 장면과 앞 대화를 기준으로 답한다(Q-31).
        lines.add("currentScene(지금 장면 제목, 지금까지의 줄거리, 방금 들은 대사, 지금 그림에 보이는 것)과 conversationSoFar(이 대화의 앞부분)를 기준으로 답한다.");
        lines.add("storySoFar에 없는 사건은 아직 일어나지 않은 일이다. 미리 말하거나 암시하지 않는다. visibleInPicture에 없는 물건이나 인물을 그림에 있다고 말하지 않는다.");
        lines.add("이 인물이 아직 모르는 것은 아는 척하거나 반대로 단정하지 않고, 아직 잘 모르겠다고 솔직하게 말한다. 확인되지 않은 사실(누가 사는지, 창문이 잠겼는지 등)을 지어내지 않는다.");
        lines.add("이미 답한 것을 다시 묻지 않는다. 아이가 정정하면 정정한 내용을 그대로 받아들인다. actionsAlreadyTaken에 있는 일은 이미 한 일로 기억한다.");
        // 답의 방향: 물은 것에 먼저 답한다, 감정·경험·관심을 받아준다, 질문은 흐름에 맞을 때만.
        lines.add("아이가 물었으면 먼저 그 질문에 답한다. 답하기 전에 다른 질문으로 넘어가지 않는다.");
        lines.add("아이가 감정이나 자기 경험을 말하면 그 말을 받아준다(예: 엄마에게 보여주고 싶었는데 부러져서 속상했구나). 해결책이나 교훈을 바로 주지 않고, 공감한 뒤 기다리는 답도 괜찮다.");
        lines.add("아이가 관심을 보인 소재를 따라간다. 아이가 자기 이야기를 꺼내면 동화로 억지로 돌리지 않는다.");
        lines.add("아이가 더 궁금해지도록 하는 질문은 흐름에 맞을 때만 한 개 덧붙인다. 모든 답을 질문으로 끝내지 않는다.");
        lines.add("무조건 칭찬하거나 교훈으로 마무리하지 않는다. 아이가 말하지 않은 의도나 감정(예: 속상한 일이 있었는지)을 지어내지 않는다.");
        lines.add("1~3문장, 반말로 답한다. 이야기 세계 안의 인물로서 실제로 겪은 것처럼 말한다.");
        // 끝내기
        lines.add("아이가 그만하고 싶다고 분명히 말하면 childWantsToEnd를 true, replyKind를 CLOSE로 하고 질문 없이 짧게 인사한다.");
        lines.add("wrapUp이 SUGGEST_RETURN이면 아이 말에 답한 뒤 이제 이야기로 돌아가 볼지 부드럽게 물어본다. wrapUp이 CLOSE이면 질문 없이 짧게 마무리 인사를 하고 replyKind를 CLOSE로 한다.");
        lines.add("아이가 모르겠어, 도와줘처럼 무엇을 말할지 모르겠다며 도움을 바랄 때만 asksForHelp를 true로 한다(이야기에 대한 질문은 도움 요청이 아니다). 이때 정답이나 다음 사건을 알려주지 말고 짧게 받아주기만 한다.");
        // 질문 초대
        if (request.inviteAnchor() != null) {
            lines.add("지금은 questionInvite 상황에서 아이에게 말을 건 직후다. 아이의 질문·감정·경험에는 대화로 반응한다.");
            lines.add("아이가 preparedActions 중 하나와 뜻이 같은 행동을 분명히 제안하면 proposedActionFamilyId에 그 id를 넣고, responseText로 뜻을 한 번 확인한다(예: 헨젤이 부르는 동안 내가 열쇠를 가져오자는 거지?). 아직 실행했다고 말하지 않는다.");
            lines.add("질문(예: 열쇠는 어디 있어?)은 행동 제안이 아니다. 아는 범위에서 답하고 proposedActionFamilyId는 null이다.");
            lines.add("preparedActions에 없는 행동을 제안하면 그 생각을 받아주되, 이야기 속에서 실행했다고 꾸미지 않는다. proposedActionFamilyId는 null이다.");
        } else {
            lines.add("새로운 분기나 선택지를 만들지 않는다. 대화일 뿐, 이야기 진행에는 영향을 주지 않는다. proposedActionFamilyId는 항상 null이다.");
        }
        lines.add("forbiddenKnowledge에 있는 내용은 사실·추측·가능성 형태로도 절대 언급하지 않는다.");
        if (safetyFragment != null && !safetyFragment.isBlank()) {
            lines.add(safetyFragment);
        }
        lines.add("위 안전 규칙에 해당하면 interactionMode를 GENTLE_REDIRECT, replyKind를 REDIRECT로 하고, 위험을 짧게 막은 뒤 안전한 화제로 부드럽게 돌아온다.");
        lines.add("그 외에는 interactionMode를 ANSWER로 하고, replyKind는 질문에 답했으면 ANSWER, 감정·경험을 받아줬으면 EMPATHY, 공감하고 기다리면 WAIT, 마무리 인사면 CLOSE로 한다.");
        lines.add("childMeaning에는 아이 말의 뜻을 30자 안팎으로 적는다. 확대 해석하지 않는다.");
        lines.add("topicTag/toneTag/valueTag는 아이의 말에서 뚜렷하게 드러날 때만 고르고, 확신이 없으면 null로 둔다.");
        lines.add("응답을 반환하기 전에 한국어 맞춤법·띄어쓰기와 캐릭터 말투 일치를 한 번 확인한다.");
        return String.join(" ", lines);
    }

    /**
     * personas.yaml 한 장을 프롬프트 문장으로 옮긴다. 시트가 없으면(아직 임포트되지 않은 스토리)
     * 성격을 지어내지 않고 "따뜻한 친구" 한 줄만 준다 - 없는 설정을 만들어 내지 않기 위해서다.
     */
    static List<String> personaLines(CompanionPersona persona) {
        if (persona == null) {
            return List.of("이 인물의 페르소나 시트가 없으므로, 따뜻하고 다정한 친구의 말투로 답한다.");
        }
        List<String> lines = new ArrayList<>();
        lines.add("인물: " + persona.castTag() + " (" + persona.ageBand() + "). " + persona.oneLiner() + ".");
        if (!persona.traits().isEmpty()) {
            lines.add("성격: " + String.join(", ", persona.traits()) + ".");
        }
        if (!persona.speechEndings().isEmpty()) {
            lines.add("말끝은 " + String.join(", ", persona.speechEndings()) + " 처럼 맺는다.");
        }
        if (!persona.catchphrases().isEmpty()) {
            lines.add("가끔 쓰는 말: " + String.join(" / ", persona.catchphrases()) + ".");
        }
        lines.add("문장 길이 성향: " + sentenceLengthHint(persona.sentenceLengthBias()) + ".");
        if (!persona.emotionAllowed().isEmpty()) {
            lines.add("표현해도 되는 감정: " + String.join(", ", persona.emotionAllowed()) + ". "
                    + persona.emotionCapNote() + ".");
        }
        if (!persona.knows().isEmpty()) {
            lines.add("이 인물이 아는 것: " + String.join("; ", persona.knows()) + ".");
        }
        if (!persona.doesNotKnow().isEmpty()) {
            lines.add("이 인물이 모르는 것(모르는 척이 아니라 정말 모른다): " + String.join("; ", persona.doesNotKnow()) + ".");
        }
        if (!persona.neverRevealsFirst().isEmpty()) {
            lines.add("아이가 물어도 먼저 말하지 않는 것: " + String.join("; ", persona.neverRevealsFirst()) + ".");
        }
        return lines;
    }

    private static String sentenceLengthHint(String bias) {
        if (bias == null) return "짧고 또렷하게";
        return switch (bias.toUpperCase()) {
            case "SHORT" -> "짧고 또렷하게(한 문장 10~20자)";
            case "LONG" -> "조금 길어도 되지만 한 문장 36자를 넘기지 않게";
            default -> "보통 길이(한 문장 10~28자)";
        };
    }

    /**
     * OpenRouter 호출(채팅완성/이미지 생성 전부 공유)이 2xx가 아닌 상태로 실패했을 때 실제
     * 원인을 서버 로그에 남긴다 - 사용자에게는 항상 안전한 고정 문구만 내려가므로, 이 로그가
     * 없으면 429 미만(예: 401/403/400 - 잘못된 키, 잘못된 파라미터, 요청 형식)으로 실패했는지조차
     * 운영 중엔 알 방법이 없다. API 키는 헤더에만 실리고 본문에는 없으므로 응답 본문을 그대로
     * 남겨도 새지 않는다. 태그(openrouter-http.failed)를 모든 호출 지점이 공유하므로, Grafana에서
     * `{app="qstory-backend"} |= "openrouter-http.failed"` 하나로 채팅완성/이미지 생성 실패를
     * 전부 모아 볼 수 있다.
     */
    private void logProviderHttpFailure(String context, int statusCode, byte[] responseBody) {
        String bodySnippet = new String(responseBody, StandardCharsets.UTF_8);
        if (bodySnippet.length() > FAILURE_BODY_LOG_LIMIT) {
            bodySnippet = bodySnippet.substring(0, FAILURE_BODY_LOG_LIMIT) + "...(truncated)";
        }
        log.warn(
                "openrouter-http.failed context={} status={} retryable={} responseBody={}",
                context, statusCode, statusCode >= 429, bodySnippet);
    }

    /**
     * generateCompanionReply()는 자신의 고정된 스키마(CompanionReply)에 강하게 결합돼 있어 재사용하기
     * 어렵다. shadow-family 생성, live-branch 생성, 3단계 라우팅 파이프라인처럼
     * 서로 다른 JSON 스키마를 쓰는 여러 호출자를 위해, 요청 조립·에러 처리 배관(plumbing)만
     * 일반화한 것이다 - 스키마 자체는 호출자가 만든다.
     *
     * <p>few-shot 예시(examples)는 system 메시지 다음, 실제 user 메시지 앞에 user/assistant 메시지
     * 쌍으로 삽입된다.
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
                logProviderHttpFailure("generateStructuredCompletion:" + schemaName, response.statusCode(), response.body());
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

    /** 기본 llmModel·few-shot 없음·temperature=0 단축 오버로드(ShadowFamilyGenerationService, LiveBranchExecutionWorker, FamilyDraftHarness). */
    public JsonNode generateStructuredCompletion(
            String systemPrompt, String userPayloadJson, ObjectNode schema, String schemaName, int maxTokens,
            ProviderErrorCode failureCode, String failureSafeDetail, RequestDeadline deadline) {
        return generateStructuredCompletion(
                llmModel, systemPrompt, List.of(), userPayloadJson, schema, schemaName, maxTokens, 0,
                failureCode, failureSafeDetail, deadline);
    }

    public record GeneratedImage(byte[] bytes, String mimeType) {}

    /**
     * 삽화 이미지 생성. 참조 이미지 하나(기존 승인 삽화)를 스타일/인물 고정용으로 함께 보낸다.
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
                logProviderHttpFailure("generateImage", response.statusCode(), response.body());
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
