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
import com.qstory.backend.story.ChildFacingTerms;
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
            com.qstory.backend.story.Anchor inviteAnchor,
            /** 장면에 맞는 호칭(노파 → 할머니/마녀). AI에게 보내는 글과 AI가 만든 글에 모두 적용한다. */
            ChildFacingTerms terms,
            /** 질문 초대의 시도 장면(replyKind TRY_FAIL) 스위치·상한과 내레이터 - 질문 초대에서만 쓰인다. */
            AttemptPolicy attemptPolicy) {

        public CompanionRequest {
            dialogue = dialogue == null ? com.qstory.backend.companionchat.DialogueInput.empty() : dialogue;
            terms = terms == null ? ChildFacingTerms.none() : terms;
            attemptPolicy = attemptPolicy == null ? AttemptPolicy.OFF : attemptPolicy;
        }

        public CompanionRequest(
                String transcript, String promptVersion, String storyTitle, String primarySpeakerId,
                List<String> allowedSpeakerIds, List<String> forbiddenKnowledge, CompanionPersona persona,
                com.qstory.backend.companionchat.DialogueInput dialogue, com.qstory.backend.story.Anchor inviteAnchor,
                ChildFacingTerms terms) {
            this(transcript, promptVersion, storyTitle, primarySpeakerId, allowedSpeakerIds, forbiddenKnowledge,
                    persona, dialogue, inviteAnchor, terms, AttemptPolicy.OFF);
        }

        public CompanionRequest(
                String transcript, String promptVersion, String storyTitle, String primarySpeakerId,
                List<String> allowedSpeakerIds, List<String> forbiddenKnowledge, CompanionPersona persona,
                com.qstory.backend.companionchat.DialogueInput dialogue, com.qstory.backend.story.Anchor inviteAnchor) {
            this(transcript, promptVersion, storyTitle, primarySpeakerId, allowedSpeakerIds, forbiddenKnowledge,
                    persona, dialogue, inviteAnchor, null, AttemptPolicy.OFF);
        }

        /** 도움 요청은 질문 초대 안에서만 받는다. */
        boolean helpRequested() {
            return inviteAnchor != null && dialogue.isHelp();
        }

        /** 시도 장면 규칙이 이번 답에 들어가는지 - 스위치가 켜져 있고, 질문 초대 중이고, 도움 버튼 답이 아닐 때만. */
        public boolean attemptRulesApply() {
            return attemptPolicy.enabled() && inviteAnchor != null && !helpRequested();
        }

        /** 이번 답에서 새 시도 장면(TRY_FAIL)을 만들 수 있는지 - 시도 장면 밖이고, 이 질문 지점의 상한이 남았을 때만. */
        public boolean canStartAttempt() {
            return attemptRulesApply() && dialogue.attempt().active() == null
                    && dialogue.attempt().tried() < attemptPolicy.maxPerAnchor();
        }

        /** 지금 시도 장면 안인지. */
        public boolean attemptActive() {
            return attemptRulesApply() && dialogue.attempt().active() != null;
        }

        /** 시도 장면의 마지막 아이 말인지 - 이 답에서 원래 질문 지점으로 돌아온다. */
        public boolean attemptLastTurn() {
            return attemptActive() && dialogue.attempt().active().turn() >= attemptPolicy.maxTurns();
        }

        /** 시도 장면 줄에 쓸 수 있는 화자 - 내레이터와 이 대화의 화자(그레텔). 마녀 등은 내레이터가 묘사만 한다. */
        public List<String> sceneSpeakerIds() {
            List<String> ids = new ArrayList<>();
            if (attemptPolicy.narratorSpeakerId() != null) ids.add(attemptPolicy.narratorSpeakerId());
            allowedSpeakerIds.stream().filter(id -> !ids.contains(id)).forEach(ids::add);
            return List.copyOf(ids);
        }

        List<String> inviteFamilyIds() {
            return inviteAnchor == null ? List.of() : inviteAnchor.actionFamilies().stream().map(ActionFamily::id).toList();
        }
    }

    /**
     * 질문 초대 시도 장면 설정.
     *
     * @param enabled           스위치(QSTORY_TRY_AND_FAIL_ENABLED)
     * @param maxPerAnchor      질문 지점 하나의 시도 장면 상한(TTS 하루 한도 보호)
     * @param maxTurns          시도 장면 안에서 아이가 말할 수 있는 횟수
     * @param narratorSpeakerId 시도 장면 내레이션 화자(이 이야기 캐스트의 내레이터). 없으면 그레텔 줄만 쓴다.
     */
    public record AttemptPolicy(boolean enabled, int maxPerAnchor, int maxTurns, String narratorSpeakerId) {
        public static final AttemptPolicy OFF = new AttemptPolicy(false, 0, 0, null);
    }

    /** 시도 장면 한 줄 - 내레이터 줄은 내레이터 목소리, 인물 줄은 그 인물 목소리로 읽는다(라이브 분기 대사와 같다). */
    public record SceneLine(String speakerId, String text) {}

    /** 시도 장면 줄 수·길이 상한. 줄마다 TTS를 한 번 부르므로 짧게 둔다. */
    static final int MAX_SCENE_LINES = 3;
    static final int MAX_SCENE_LINE_LENGTH = 80;

    /**
     * @param replyKind              ANSWER(물은 것에 답함) / EMPATHY(감정·경험을 받아줌) / WAIT(공감하고 기다림)
     *                               / CLOSE(마무리 인사) / REDIRECT(안전 규칙으로 돌림)
     *                               / TRY_FAIL(질문 초대에서 아이가 낸 준비되지 않은 행동을 해 보는 시도 장면을 시작함 - attemptScene)
     *                               / ATTEMPT_END(시도 장면을 마치고 원래 질문 지점으로 돌아옴)
     * @param childWantsToEnd        아이가 그만 이야기하고 싶다고 분명히 말했는지
     * @param childMeaning           아이 말의 뜻을 짧게 - 리포트·기록용, 확대 해석하지 않는다
     * @param asksForHelp            "모르겠어"·"도와줘"처럼 도움을 바라는지
     * @param proposedActionFamilyId 질문 초대에서 아이가 실행 가능한 행동을 제안했으면 그 family id(뜻 확인 전)
     */
    public record CompanionReply(
            String interactionMode, String responseText, String speakerId,
            String topicTag, String toneTag, String valueTag,
            String replyKind, boolean childWantsToEnd, String childMeaning, boolean asksForHelp,
            String proposedActionFamilyId,
            /** TRY_FAIL일 때만 - 시도 장면 줄(내레이터·인물). 그 밖에는 빈 목록. responseText는 이 장면 뒤 그레텔이 묻는 말이다. */
            List<SceneLine> attemptScene) {

        public CompanionReply {
            attemptScene = attemptScene == null ? List.of() : List.copyOf(attemptScene);
        }

        public CompanionReply(
                String interactionMode, String responseText, String speakerId,
                String topicTag, String toneTag, String valueTag,
                String replyKind, boolean childWantsToEnd, String childMeaning, boolean asksForHelp,
                String proposedActionFamilyId) {
            this(interactionMode, responseText, speakerId, topicTag, toneTag, valueTag, replyKind, childWantsToEnd,
                    childMeaning, asksForHelp, proposedActionFamilyId, List.of());
        }
    }

    static final List<String> REPLY_KINDS = List.of("ANSWER", "EMPATHY", "WAIT", "CLOSE", "REDIRECT");

    /**
     * 시도 장면 시작 - 아이가 낸 준비되지 않은 행동(창문으로 나가자, 몰래 가져가자 등)을 거절하지 않고 이야기 속에서 해 보다가
     * 막히는 짧은 장면(attemptScene)을 보여 주고, 그 상황에서 그레텔이 아이에게 묻는다. 이야기 주 흐름은 바뀌지 않는다.
     */
    public static final String TRY_FAIL = "TRY_FAIL";

    /** 시도 장면을 마치고 "다른 방법을 찾아보자"며 원래 질문 지점으로 돌아온다. */
    public static final String ATTEMPT_END = "ATTEMPT_END";

    /** 이 요청에서 고를 수 있는 replyKind - TRY_FAIL은 새 시도 장면을 만들 수 있을 때만, ATTEMPT_END는 시도 장면 안에서만. */
    static List<String> replyKinds(CompanionRequest request) {
        List<String> kinds = new ArrayList<>(REPLY_KINDS);
        if (request.canStartAttempt()) {
            kinds.add(TRY_FAIL);
        }
        if (request.attemptActive()) {
            kinds.add(ATTEMPT_END);
        }
        return List.copyOf(kinds);
    }

    /**
     * 그레텔 대화 시스템 프롬프트(companionSystemPrompt) 버전 - 지시 문장을 바꾸면 올린다. 대화 원장에는 라우팅
     * 프롬프트 버전과 함께 남는다(ConversationRecordService.recordCompanionTurn). V2_1: 질문 초대에서 준비되지 않은 행동을
     * 하겠다고 약속하는 답을 막는 지시를 더했다.
     */
    public static final String COMPANION_PROMPT_VERSION = "GRETEL_DIALOGUE_V2_1";

    /**
     * V2 + 질문 초대의 시도 장면(TRY_FAIL·ATTEMPT_END) 지시. 스위치(QSTORY_TRY_AND_FAIL_ENABLED)를 끄면 V2_1이다.
     * V4: 하고 싶다는 말·준비되지 않은 이동, 앞에서 나온 준비되지 않은 행동에 대한 동의도 시도 장면을 시작한다.
     *     질문 초대에서 준비되지 않은 행동을 하겠다고 약속하는 답을 막는다(스위치를 끈 V2_1에도 같은 지시가 들어간다).
     * V5: 시도 장면의 그레텔 답(TRY_FAIL·장면 안·ATTEMPT_END)도 준비된 행동을 둘 이상 내놓지 않고, 장면 줄에 대화하는 아이를
     *     인물로 넣지 않는다(검증에서도 걸러 낸다). 스위치를 끈 쪽(V2_1)은 그대로다.
     */
    public static final String TRY_AND_FAIL_PROMPT_VERSION = "GRETEL_DIALOGUE_V5";

    /** 대화 원장에 남길 그레텔 대화 지시 버전. */
    public static String companionPromptVersion(boolean tryAndFail) {
        return tryAndFail ? TRY_AND_FAIL_PROMPT_VERSION : COMPANION_PROMPT_VERSION;
    }

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
                                    buildCompanionChatBody(request, false).getBytes(StandardCharsets.UTF_8))))
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
            return requireValidCompanionReply(parsed, request);
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.", true, error);
        }
    }

    /**
     * generateCompanionReply()와 같은 요청·같은 검증이지만 OpenRouter 스트리밍으로 받는다. 스키마 순서대로
     * interactionMode 다음에 responseText가 오므로(2026-10 실측: 첫 토큰 약 2.6초, responseText 완성 약 3.0초,
     * 전체 약 3.4초), responseText 문자열이 닫히는 순간 같은 정리(normalizeKoreanResponseText, 호칭 규칙)와
     * 길이 검사를 거친 문장을 onResponseText로 먼저 넘긴다 - 말풍선과 음성 요청을 나머지 필드(태그·replyKind 등)를
     * 기다리지 않고 시작하게 하려는 것이다. 반환값은 전체 JSON을 끝까지 받은 뒤의 검증 결과로 예전과 같다.
     *
     * <p>문장을 쪼개 TTS를 여러 번 부르지는 않는다 - responseText 전체가 첫 토큰 뒤 0.3~0.4초 안에 다 나와서
     * 문장 단위로 나눠 얻는 시간이 거의 없고, 하루 요청 수 한도(Gemini TTS)만 두세 배로 빨리 닳는다.
     */
    public CompanionReply streamCompanionReply(
            CompanionRequest request, RequestDeadline deadline, java.util.function.Consumer<String> onResponseText) {
        long startedAt = System.nanoTime();
        java.io.InputStream body = null;
        try {
            HttpRequest httpRequest = deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/chat/completions"))
                            .headers(baseHeaders())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    buildCompanionChatBody(request, true).getBytes(StandardCharsets.UTF_8))))
                    .build();
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            body = response.body();
            if (response.statusCode() / 100 != 2) {
                byte[] failureBody = body.readNBytes(FAILURE_BODY_LOG_LIMIT * 4);
                boolean detailPresent = readErrorDetail(failureBody) != null;
                logProviderHttpFailure("streamCompanionReply", response.statusCode(), failureBody);
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_RESPONSE_FAILED,
                        detailPresent ? "아이의 말에 답을 준비하지 못했어요." : "AI 응답 서버에 연결하지 못했어요.",
                        response.statusCode() == 400 || response.statusCode() >= 429);
            }
            // HttpClient의 timeout은 응답 헤더까지만 - 토큰이 멈춘 채 연결만 남으면 요청 시간 예산이 끝날 때 닫는다.
            java.io.InputStream watched = body;
            java.util.concurrent.CompletableFuture.delayedExecutor(
                            Math.max(1, deadline.remaining().toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> closeQuietly(watched));
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(body, StandardCharsets.UTF_8));
            StringBuilder content = new StringBuilder();
            boolean textSent = false;
            long firstTokenMs = -1;
            long responseTextMs = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue; // 빈 줄과 ": OPENROUTER PROCESSING" 같은 주석
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode event = objectMapper.readTree(data);
                if (event.has("error")) {
                    log.warn("openrouter-stream.failed context=streamCompanionReply error={}",
                            truncate(event.path("error").toString()));
                    throw new ProviderException(
                            ProviderErrorCode.OPENROUTER_RESPONSE_FAILED, "아이의 말에 답을 준비하지 못했어요.", true);
                }
                JsonNode delta = event.path("choices").path(0).path("delta").path("content");
                if (!delta.isTextual() || delta.asText().isEmpty()) {
                    continue;
                }
                if (firstTokenMs < 0) {
                    firstTokenMs = (System.nanoTime() - startedAt) / 1_000_000;
                }
                content.append(delta.asText());
                if (!textSent) {
                    String token = completedTopLevelStringToken(content, "responseText");
                    if (token != null) {
                        textSent = true;
                        responseTextMs = (System.nanoTime() - startedAt) / 1_000_000;
                        String early = earlyResponseText(objectMapper.readValue(token, String.class), request);
                        // 시도 장면이 있으면 답 문장을 먼저 보내지 않는다 - 장면 줄(내레이터·인물)을 먼저 들려준 뒤 그레텔이
                        // 묻는 말이 나와야 한다. attemptScene은 스키마에서 responseText보다 앞이라 이미 다 와 있다.
                        if (early != null && request.canStartAttempt() && !EMPTY_ATTEMPT_SCENE.matcher(content).find()) {
                            early = null;
                        }
                        if (early != null) {
                            onResponseText.accept(early);
                        }
                    }
                }
            }
            log.info("openrouter.companion-stream first_token_ms={} response_text_ms={} total_ms={}",
                    firstTokenMs, responseTextMs, (System.nanoTime() - startedAt) / 1_000_000);
            if (content.length() == 0) {
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.");
            }
            return requireValidCompanionReply(objectMapper.readTree(content.toString()), request);
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.", true, error);
        } finally {
            closeQuietly(body);
        }
    }

    private CompanionReply requireValidCompanionReply(JsonNode parsed, CompanionRequest request) {
        CompanionReply reply = validateCompanionReply(parsed, request);
        if (reply == null) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "아이에게 들려줄 답을 안전하게 확인하지 못했어요.");
        }
        return reply;
    }

    /**
     * 스트리밍 중 먼저 받은 responseText를 validateCompanionReply()가 할 정리·길이 검사 그대로 거친다 - 최종 답의
     * responseText와 같은 문장이 된다. 길이가 맞지 않으면 null(먼저 보내지 않고 최종 검증에 맡긴다).
     */
    String earlyResponseText(String raw, CompanionRequest request) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || text.length() > 160) {
            return null;
        }
        return request.terms().apply(routeResultValidator.normalizeKoreanResponseText(text));
    }

    /**
     * 아직 다 오지 않은 JSON 앞부분에서, 최상위 객체의 key 값이 문자열이고 그 문자열이 닫혔으면 따옴표를 포함한
     * 문자열 토큰을 돌려준다(아직이면 null). 중첩 객체·배열 안의 같은 이름은 보지 않는다.
     */
    static String completedTopLevelStringToken(CharSequence json, String key) {
        int depth = 0;
        boolean afterColon = false;
        String lastKey = null;
        int length = json.length();
        int index = 0;
        while (index < length) {
            char c = json.charAt(index);
            if (c == '"') {
                int end = index + 1;
                while (end < length && json.charAt(end) != '"') {
                    end += json.charAt(end) == '\\' ? 2 : 1;
                }
                if (end >= length) {
                    return null;
                }
                if (depth == 1) {
                    String token = json.subSequence(index, end + 1).toString();
                    if (afterColon) {
                        if (key.equals(lastKey)) {
                            return token;
                        }
                        afterColon = false;
                    } else {
                        lastKey = token.substring(1, token.length() - 1);
                    }
                }
                index = end + 1;
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (depth == 1 && c == ':') {
                afterColon = true;
            } else if (depth == 1 && c == ',') {
                afterColon = false;
                lastKey = null;
            }
            index++;
        }
        return null;
    }

    private static void closeQuietly(java.io.InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // 이미 닫혔거나 연결이 끊겼다.
        }
    }

    /** 스트리밍 중인 답 앞부분에서 시도 장면이 비어 있음("attemptScene":[])을 찾는다. */
    static final java.util.regex.Pattern EMPTY_ATTEMPT_SCENE =
            java.util.regex.Pattern.compile("\"attemptScene\"\\s*:\\s*\\[\\s*\\]");

    private static String truncate(String value) {
        return value.length() > FAILURE_BODY_LOG_LIMIT ? value.substring(0, FAILURE_BODY_LOG_LIMIT) + "...(truncated)" : value;
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
        if (!replyKinds(request).contains(replyKind)) {
            return null;
        }
        boolean startsAttempt = TRY_FAIL.equals(replyKind);
        if ((startsAttempt || ATTEMPT_END.equals(replyKind)) && "GENTLE_REDIRECT".equals(interactionMode)) {
            // 안전 규칙으로 막았다면 시도 장면이 아니다 - 안전 쪽(REDIRECT)으로 읽는다.
            replyKind = "REDIRECT";
            startsAttempt = false;
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
        // 시도 장면 시작·끝은 준비된 행동 제안이 아니다 - 실행 확인을 띄우지 않고, 대화를 끝내지도 않는다.
        boolean attemptTurn = startsAttempt || ATTEMPT_END.equals(replyKind);
        if (attemptTurn) {
            proposed = null;
        }
        // 시도 장면의 마지막 아이 말인데 모델이 장면 안 대화를 이어 갔으면, 그 답으로 원래 질문 지점에 돌아온다.
        if (request.attemptLastTurn() && proposed == null && List.of("ANSWER", "EMPATHY", "WAIT").contains(replyKind)) {
            replyKind = ATTEMPT_END;
            attemptTurn = true;
        }
        List<SceneLine> scene = startsAttempt ? sceneLines(value.path("attemptScene"), request) : List.of();
        // 도움 버튼으로 온 요청은 그 자체가 도움이다 - 다시 "도와줄까?"를 띄우지 않는다.
        boolean asksForHelp = !attemptTurn && !request.helpRequested() && value.path("asksForHelp").asBoolean(false);
        boolean triedAndFailed = attemptTurn;
        ChildFacingTerms terms = request.terms();
        return new CompanionReply(
                interactionMode, terms.apply(routeResultValidator.normalizeKoreanResponseText(responseText)), speakerId,
                topicTag, toneTag, valueTag,
                replyKind, !triedAndFailed && value.path("childWantsToEnd").asBoolean(false), terms.apply(childMeaning),
                asksForHelp, proposed, scene);
    }

    /**
     * 시도 장면 줄을 읽는다. 허용된 화자(내레이터·그레텔)가 아니거나 빈 줄, 대화하는 아이를 장면 인물로 넣은 줄은
     * 버리고(고칠 수 있으면 고치고), 길이를 자르고, 호칭·맞춤법 정리를 답과 똑같이 한다. 쓸 줄이 하나도 없으면 빈 목록 - 그래도 시도 장면은 시작하고 그레텔 말(responseText)로 이어 간다.
     */
    List<SceneLine> sceneLines(JsonNode node, CompanionRequest request) {
        List<String> speakers = request.sceneSpeakerIds();
        List<SceneLine> lines = new ArrayList<>();
        for (JsonNode line : node) {
            String speaker = line.path("speakerId").asText("").trim();
            String text = line.path("text").asText("").trim();
            if (!speakers.contains(speaker) || text.isEmpty()) {
                continue;
            }
            String withoutChild = sceneLineWithoutChild(text);
            if (withoutChild == null) {
                log.info("companion.attempt-scene line-dropped reason=child-in-scene");
                continue;
            }
            text = withoutChild;
            if (text.length() > MAX_SCENE_LINE_LENGTH) {
                text = text.substring(0, MAX_SCENE_LINE_LENGTH);
            }
            lines.add(new SceneLine(speaker, request.terms().apply(routeResultValidator.normalizeKoreanResponseText(text))));
            if (lines.size() == MAX_SCENE_LINES) {
                break;
            }
        }
        return List.copyOf(lines);
    }

    /** 장면 줄에서 대화하는 아이를 가리키는 말(아이·너·네가·친구). "두 아이", "아이들", "아이고", "너무"는 아니다. */
    private static final String CHILD_WORD = "(?:아이|너|(?:우리 )?친구)";
    static final java.util.regex.Pattern CHILD_IN_SCENE = java.util.regex.Pattern.compile(
            "(?<!두 )(?<![가-힣])(?:" + CHILD_WORD + "(?:는|가|도|를|와|과|랑|이랑|하고|에게|의)?|네가)(?![가-힣])");
    /** "그레텔과 아이는", "헨젤, 그리고 친구는" - 앞 인물만 남긴다. */
    private static final java.util.regex.Pattern CHILD_AFTER_CAST = java.util.regex.Pattern.compile(
            "([가-힣]+)(?:(?:과|와|이랑|랑|하고) |, 그리고 |, )(?<!두 )" + CHILD_WORD + "(는|가|도|를|의)?(?![가-힣])");
    /** "아이와 그레텔은" - 아이 부분을 뺀다. */
    private static final java.util.regex.Pattern CHILD_BEFORE_CAST = java.util.regex.Pattern.compile(
            "(?<!두 )(?<![가-힣])" + CHILD_WORD + "(?:와|과|랑|이랑|하고|, 그리고|,) ");

    /**
     * 시도 장면 줄은 이야기 속 인물만 행동한다(대화하는 아이는 이야기 밖에 있다). "그레텔과 아이는"처럼 캐스트와 함께 묶인
     * 아이는 빼고 앞 인물의 받침에 맞게 조사를 고친다. 그래도 아이·너·친구가 인물로 남으면 그 줄은 쓰지 않는다(null).
     */
    static String sceneLineWithoutChild(String text) {
        if (!CHILD_IN_SCENE.matcher(text).find()) {
            return text;
        }
        java.util.regex.Matcher after = CHILD_AFTER_CAST.matcher(text);
        StringBuilder fixed = new StringBuilder();
        while (after.find()) {
            String name = after.group(1);
            after.appendReplacement(fixed, java.util.regex.Matcher.quoteReplacement(name + particleAfter(name, after.group(2))));
        }
        after.appendTail(fixed);
        String repaired = CHILD_BEFORE_CAST.matcher(fixed).replaceAll("");
        return CHILD_IN_SCENE.matcher(repaired).find() ? null : repaired;
    }

    /** 아이 뒤에 붙었던 조사를 앞 인물 이름의 받침에 맞춘다(는→은, 가→이, 를→을). */
    private static String particleAfter(String name, String particle) {
        if (particle == null) {
            return "";
        }
        char last = name.charAt(name.length() - 1);
        boolean batchim = last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
        return switch (particle) {
            case "는" -> batchim ? "은" : "는";
            case "가" -> batchim ? "이" : "가";
            case "를" -> batchim ? "을" : "를";
            default -> particle;
        };
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

    private String buildCompanionChatBody(CompanionRequest request, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", llmModel);
        if (stream) {
            root.put("stream", true);
        }

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", companionSystemPrompt(request));

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        // 장면 대사(캐스트 이름)·금지 지식 문구에 남은 "노파"가 AI에 닿지 않게 보내는 글도 거른다.
        userMessage.put("content", request.terms().apply(companionUserPayload(request).toString()));

        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", "q_story_companion_v2");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", companionSchema(
                request.allowedSpeakerIds(), request.inviteFamilyIds(), replyKinds(request),
                request.canStartAttempt() ? request.sceneSpeakerIds() : List.of()));

        root.putObject("provider").put("require_parameters", true);
        ObjectNode reasoning = root.putObject("reasoning");
        reasoning.put("effort", "minimal");
        reasoning.put("exclude", true);
        root.put("temperature", 0);
        // 답(160자)과 태그만 담은 JSON이라 400이면 넉넉하다 - 상한이 낮을수록 잘못 길어지는 답이 빨리 끊긴다.
        root.put("max_tokens", 400);
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
        if (request.attemptRulesApply()) {
            ObjectNode attempts = payload.putObject("attempts");
            attempts.put("triedAtThisQuestion", dialogue.attempt().tried());
            attempts.put("maxAtThisQuestion", request.attemptPolicy().maxPerAnchor());
            attempts.put("canStartNew", request.canStartAttempt());
            if (request.canStartAttempt()) {
                ArrayNode speakers = attempts.putArray("sceneSpeakerIds");
                request.sceneSpeakerIds().forEach(speakers::add);
                attempts.put("narratorSpeakerId", request.attemptPolicy().narratorSpeakerId());
            }
            var active = dialogue.attempt().active();
            if (active != null) {
                ObjectNode inProgress = payload.putObject("attemptInProgress");
                inProgress.put("whatWasTried", active.tried());
                inProgress.put("sceneSoFar", active.scene());
                inProgress.put("turn", active.turn());
                inProgress.put("maxTurns", request.attemptPolicy().maxTurns());
                inProgress.put("lastTurn", request.attemptLastTurn());
            }
        }
        if (request.helpRequested()) {
            ObjectNode help = payload.putObject("helpRequest");
            help.put("step", dialogue.help().step());
            help.put("totalSteps", dialogue.help().total());
            help.put("hint", dialogue.help().hint());
        }
        return payload;
    }

    /**
     * @param sceneSpeakerIds 비어 있지 않으면 시도 장면(attemptScene)을 만들 수 있다 - responseText보다 앞에 둔다. 스트리밍에서
     *                        답 문장을 먼저 보낼 때 장면이 있는지 이미 알아야(장면을 먼저 들려줘야) 하기 때문이다.
     */
    ObjectNode companionSchema(
            List<String> allowedSpeakerIds, List<String> inviteFamilyIds, List<String> replyKinds,
            List<String> sceneSpeakerIds) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode interactionMode = properties.putObject("interactionMode");
        interactionMode.put("type", "string");
        ArrayNode modeEnum = interactionMode.putArray("enum");
        com.qstory.backend.common.enums.CompanionInteractionMode.ALL_NAMES.forEach(modeEnum::add);

        if (!sceneSpeakerIds.isEmpty()) {
            ObjectNode attemptScene = properties.putObject("attemptScene");
            attemptScene.put("type", "array");
            attemptScene.put("maxItems", MAX_SCENE_LINES);
            attemptScene.put("description", "replyKind가 TRY_FAIL일 때만 2~3줄, 그 밖에는 반드시 빈 배열.");
            ObjectNode item = attemptScene.putObject("items");
            item.put("type", "object");
            ObjectNode itemProperties = item.putObject("properties");
            ObjectNode lineSpeaker = itemProperties.putObject("speakerId");
            lineSpeaker.put("type", "string");
            ArrayNode lineSpeakerEnum = lineSpeaker.putArray("enum");
            sceneSpeakerIds.forEach(lineSpeakerEnum::add);
            addStringProperty(itemProperties, "text", 1, MAX_SCENE_LINE_LENGTH);
            item.putArray("required").add("speakerId").add("text");
            item.put("additionalProperties", false);
        }

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
        replyKinds.forEach(replyKindEnum::add);
        properties.putObject("childWantsToEnd").put("type", "boolean");
        addStringProperty(properties, "childMeaning", 0, 80);
        properties.putObject("asksForHelp").put("type", "boolean");
        // 질문 초대가 아니면 null만 허용한다 - 상시 대화는 이야기 진행을 바꾸지 않는다.
        addNullableLabelEnum(properties, "proposedActionFamilyId", inviteFamilyIds);

        ArrayNode required = schema.putArray("required");
        required.add("interactionMode");
        if (!sceneSpeakerIds.isEmpty()) {
            required.add("attemptScene");
        }
        required.add("responseText").add("speakerId")
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
        // 답의 방향: 물은 것에 먼저 답한다, 감정·경험·관심을 받아준다.
        lines.add("아이가 물었으면 먼저 그 질문에 답한다. 답하기 전에 다른 질문으로 넘어가지 않는다.");
        // V2(PM 피드백 - "정직하게 대답만 한다"): 사실은 짧게, 생각은 아이에게 돌려준다. 막다른 답으로 끝내지 않는다.
        lines.add("사실은 1~2문장으로 짧게 말한다. 그다음 대개는 아이가 자기 생각을 더 말할 수 있게 열린 질문이나 상상해 보자는 말 하나로 생각을 아이에게 돌려준다(예: 너는 어떤 방법이 있을 것 같아? / 너라면 어떻게 했을 것 같아? / 왜 그랬을 것 같아?).");
        lines.add("돌려주는 질문은 응·아니로 끝나는 질문보다 아이 생각을 묻는 질문으로 하고, 6~9세가 바로 답할 수 있게 쉽고 짧게 한다. 한 답에 질문은 하나만 한다.");
        lines.add("막다른 답으로 끝내지 않는다. '방법이 없어', '모르겠어'처럼 닫고 끝내지 말고, 모르면 모른다고 짧게 말한 뒤 '우리 같이 생각해 볼까? 너는 어떤 방법이 떠올라?'처럼 아이 생각을 묻는다.");
        lines.add("아이가 같은 것을 다시 묻거나 말을 바꿔 다시 물으면(예: 다른 좋은 방법은 없을까? 를 또 물음) 앞에서 한 답을 되풀이하지 않는다. 앞 답은 한마디로만 짚고, 지금 장면에 보이는 단서 하나를 짚거나 아이에게 어떤 생각이 드는지 묻는다.");
        lines.add("매번 질문하지는 않는다. 아이가 감정이나 자기 경험을 말하면 그 말을 받아준다(예: 엄마에게 보여주고 싶었는데 부러져서 속상했구나). 해결책이나 교훈을 바로 주지 않고, 공감한 뒤 기다리는 답도 괜찮다.");
        lines.add("바로 앞에서 네가 물은 것에 아이가 답했으면 그 답을 먼저 받아 준다. 이때는 새 질문을 꼭 붙이지 않아도 된다 - 질문을 연달아 몰아붙이지 않는다.");
        lines.add("아이가 인사하거나, 그만하고 싶어 하거나, 이야기를 계속 듣고 싶다고 하면 질문하지 않고 짧게 답한다.");
        lines.add("아이가 관심을 보인 소재를 따라간다. 아이가 자기 이야기를 꺼내면 동화로 억지로 돌리지 않는다.");
        lines.add("무조건 칭찬하거나 교훈으로 마무리하지 않는다. 아이가 말하지 않은 의도나 감정(예: 속상한 일이 있었는지)을 지어내지 않는다.");
        lines.add("모두 합쳐 1~3문장, 반말로 답한다. 이야기 세계 안의 인물로서 실제로 겪은 것처럼 말한다. 아이를 어머니·아저씨처럼 다른 호칭으로 부르지 않는다.");
        // 끝내기
        lines.add("아이가 그만하고 싶다고 분명히 말하거나 인사하며 떠나거나 이야기를 계속 듣겠다고 하면 childWantsToEnd를 true, replyKind를 CLOSE로 하고 질문 없이 짧게 인사한다.");
        lines.add("wrapUp이 SUGGEST_RETURN이면 아이 말에 짧게 답한 뒤, 생각을 돌려주는 질문 대신 이제 이야기로 돌아가 볼지 부드럽게 물어본다(다른 질문은 붙이지 않는다). wrapUp이 CLOSE이면 어떤 질문도 하지 않고(돌아갈지도 묻지 않는다) 짧게 마무리 인사를 하고 replyKind를 CLOSE로 한다.");
        if (request.helpRequested()) {
            // 도움 버튼 - 미리 쓴 도움 대사(hint)는 방향일 뿐, 앞 대화에 이어지게 말한다(PM 피드백: 대화 중에 기본 멘트가 나옴).
            lines.add("아이가 도와줘 버튼을 눌렀다(helpRequest). helpRequest.hint는 이번 도움 단계에서 줄 도움의 방향이다. hint가 짚는 단서나 생각할 거리를 따르되, conversationSoFar에서 이미 나온 내용은 되풀이하지 않고 지금 대화에 이어지게 말한다.");
            lines.add("hint보다 더 많이 알려 주지 않는다. 정답·다음 사건·hint에 없는 방법을 말하지 않는다. 1~2문장으로 단서를 짚고, 아이 생각을 묻는 열린 질문 하나로 끝낸다. replyKind는 ANSWER, asksForHelp는 false, proposedActionFamilyId는 null이다.");
        } else {
            lines.add("아이가 모르겠어, 도와줘처럼 무엇을 말할지 모르겠다며 도움을 바랄 때만 asksForHelp를 true로 한다(이야기에 대한 질문은 도움 요청이 아니다). 이때 정답이나 다음 사건을 알려주지 말고, 괜찮다고 받아 준 뒤 지금 장면에서 무엇이 궁금한지 쉽게 묻는다.");
        }
        // 질문 초대
        if (request.inviteAnchor() != null) {
            lines.add("지금은 questionInvite 상황에서 아이에게 말을 건 직후다. 아이의 질문·감정·경험에는 대화로 반응한다.");
            lines.add("아이가 preparedActions 중 하나와 뜻이 같은 행동을 분명히 제안하면 proposedActionFamilyId에 그 id를 넣고, responseText로 뜻을 한 번 확인한다(예: 헨젤이 부르는 동안 내가 열쇠를 가져오자는 거지?). 아직 실행했다고 말하지 않는다.");
            lines.add("질문(예: 열쇠는 어디 있어?)은 행동 제안이 아니다. 아는 범위에서 답하고 proposedActionFamilyId는 null이다.");
            // V2(PM 피드백 - "그럼 살펴보자"에 동의했는데 이야기에서 그 행동이 나오지 않음).
            lines.add("앞에서 네가 preparedActions 중 하나와 뜻이 같은 행동을 말했고 아이가 그것에 동의하면(예: 네가 '새가 어디로 가는지 지켜볼까?'라고 한 뒤 아이가 '그래', '그럼 살펴보자', '좋아 그렇게 하자'), 아이가 그 행동을 제안한 것으로 보고 proposedActionFamilyId에 그 id를 넣고 뜻을 한 번 확인한다.");
            if (request.attemptRulesApply()) {
                lines.addAll(attemptLines(request));
            } else {
                lines.add("preparedActions에 없는 행동을 제안하면 그 생각을 받아주되, 이야기 속에서 실행했다고 꾸미거나 하겠다고 약속하지 않는다. proposedActionFamilyId는 null이다.");
            }
            // 방어(운영 HG-F04: 시도 장면 대신 "우리 같이 저 빛나는 길을 따라 걸어가 볼까?"로 말로만 동의했다).
            lines.add("preparedActions에 없는 행동(어딘가로 가기·따라가기·만지기 등)에 '같이 가 보자', '좋아 그렇게 하자', '~할게'처럼 하겠다고 동의·약속하거나 하는 중인 것처럼 말하지 않는다 - 이야기는 그 행동을 보여 주지 않는다. "
                    + (request.attemptRulesApply()
                            ? "시도 장면을 시작할 수 있으면 시도 장면으로, 아니면 그 생각을 받아 주고 아이 생각을 묻는다."
                            : "그 생각을 받아 주고 아이 생각을 묻는다."));
            lines.add("네가 먼저 '~해 볼까?'처럼 할 일을 제안할 때는 preparedActions에 있는 행동만 제안한다. preparedActions에 없는 행동(예: 집 주변을 살펴보자, 창문을 들여다보자)은 이야기가 보여 줄 수 없으니 할 일로 제안하지 않고, 대신 아이 생각을 묻는다.");
            if (request.attemptRulesApply()) {
                // 지난 평가에서 "그럼 어떻게 해?"에 준비된 두 방법을 바로 나열한 답이 나왔다 - 정답을 먼저 주지 않는다.
                lines.add("아이가 '그럼 어떻게 해?', '어떻게 하면 돼?'처럼 방법을 물어도 preparedActions의 방법을 바로 알려 주거나 둘 이상 나열하지 않는다. 지금 장면에 보이는 단서 하나만 짚고 아이 생각을 묻는다. 아이가 앞에서 이미 두 번 넘게 모르겠다고 했을 때만 한 가지를 '~해 볼까?'로 조심스럽게 제안한다.");
            }
            if (request.inviteAnchor().actionFamilies().isEmpty()) {
                lines.add("이 질문 지점에는 preparedActions가 없다 - 대화만 하는 곳이다. '집 주변을 살펴보면 어떨까?', '창문을 보자' 같은 할 일을 절대 제안하지 않는다. 아이가 뭘 해야 하냐고 물으면 할 일 대신 무엇이 궁금한지, 이 장면의 인물에게 무엇을 물어보고 싶은지 아이 생각을 묻는다.");
                lines.add(request.attemptRulesApply()
                        ? "아이가 무언가를 하자고 하면 위의 시도 장면 규칙을 따른다. 시도 장면을 더 만들 수 없으면 좋은 생각이라고 받아 주되 '남매가 어떻게 하는지 이야기에서 같이 보자'처럼 이야기에서 보게 된다고 말한다."
                        : "아이가 무언가를 하자고 하거나 동의하면 좋은 생각이라고 받아 주되 '남매가 어떻게 하는지 이야기에서 같이 보자'처럼 이야기에서 보게 된다고 말한다.");
            }
        } else {
            lines.add("새로운 분기나 선택지를 만들지 않는다. 대화일 뿐, 이야기 진행에는 영향을 주지 않는다. proposedActionFamilyId는 항상 null이다.");
            lines.add("이야기 속에서 할 일을 '~해 보자'고 제안하거나 약속하지 않는다 - 상시 대화로는 이야기가 바뀌지 않는다. 아이가 하고 싶은 일을 말하면 그 생각을 받아 주고, 남매가 어떻게 하는지는 이야기에서 같이 보자고 한다.");
        }
        lines.add("forbiddenKnowledge에 있는 내용은 사실·추측·가능성 형태로도 절대 언급하지 않는다.");
        if (safetyFragment != null && !safetyFragment.isBlank()) {
            lines.add(safetyFragment);
        }
        lines.add("위 안전 규칙에 해당하면 interactionMode를 GENTLE_REDIRECT, replyKind를 REDIRECT로 하고, 위험을 짧게 막은 뒤 안전한 화제로 부드럽게 돌아온다.");
        lines.add("그 외에는 interactionMode를 ANSWER로 하고, replyKind는 질문에 답했으면 ANSWER, 감정·경험을 받아줬으면 EMPATHY, 공감하고 기다리면 WAIT, 마무리 인사면 CLOSE로 한다.");
        if (request.attemptRulesApply()) {
            lines.add("시도 장면을 시작했으면 interactionMode는 ANSWER, replyKind는 TRY_FAIL이고, 시도 장면을 마치고 원래 질문으로 돌아오면 replyKind는 ATTEMPT_END다. 창문·굴뚝으로 나가기, 마녀에게 부탁하기, 새를 부르기, 몰래 가져가기, 거짓말, 혼자 도망치기는 위 안전 규칙의 위험·폭력이 아니므로 GENTLE_REDIRECT로 막지 않는다.");
        }
        lines.add("childMeaning에는 아이 말의 뜻을 30자 안팎으로 적는다. 확대 해석하지 않는다.");
        lines.add("topicTag/toneTag/valueTag는 아이의 말에서 뚜렷하게 드러날 때만 고르고, 확신이 없으면 null로 둔다.");
        lines.add("응답을 반환하기 전에 한국어 맞춤법·띄어쓰기와 캐릭터 말투 일치를 한 번 확인한다.");
        // 페르소나·금지 지식 문구에 남은 "노파"를 장면에 맞는 호칭으로 바꾼 뒤, 호칭 규칙은 바꾸지 않고 붙인다.
        String prompt = request.terms().apply(String.join(" ", lines));
        String namingRule = request.terms().promptRule();
        return namingRule == null ? prompt : prompt + " " + namingRule;
    }

    /**
     * V5(운영 재확인 HG-F04 - 시도 장면 뒤 "저 새를 그냥 가만히 지켜볼까? 아니면 말을 건네볼까?"): 시도 장면의 그레텔 답
     * (TRY_FAIL·장면 안 대화·ATTEMPT_END)도 준비된 방법을 나란히 내놓지 않는다.
     */
    static final String ATTEMPT_REPLY_ONE_ACTION = "시도 장면의 그레텔 답(TRY_FAIL의 responseText, 장면 안 대화, ATTEMPT_END)에서는 막힌 것을 짧게 받아 주고 '다른 방법이 있을까?', '너는 어떻게 하면 좋을 것 같아?'처럼 아이 생각을 묻는 열린 질문 하나로 끝낸다. preparedActions의 행동을 둘 이상 늘어놓거나 '~할까? 아니면 ~할까?'처럼 고르게 하지 않는다(나쁜 예: 저 새를 가만히 지켜볼까? 아니면 말을 건네볼까? / 새를 지켜보거나 말을 걸어보면 어떨까? / 다른 길을 찾아볼까, 아니면 다른 생각이 있어?). '아니면'으로 두 갈래를 내놓지 않는다. 준비된 행동을 넌지시 꺼내더라도 하나만, 아이가 앞에서 이미 두 번 넘게 모르겠다고 했을 때만 꺼낸다.";

    /**
     * 질문 초대의 시도 장면 지시(CTO 결정 - PM 피드백 "해 보고 실패하는 모습을 보여 주자"를 넓힘): 다치거나 남을 해치는 행동만
     * 그대로 막고(GENTLE_REDIRECT), 그 밖에 준비되지 않은 행동은 해 보다가 막히는 짧은 장면을 만든다. 그 장면 안에서 아이와
     * 몇 번 더 이야기한 뒤 원래 질문 지점으로 돌아온다. 무섭지 않게, 처벌·들킴 없이, 이야기 주 흐름(열쇠·탈출·결말)은 바꾸지 않는다.
     */
    static List<String> attemptLines(CompanionRequest request) {
        List<String> lines = new ArrayList<>();
        lines.add("시도 장면 공통: 장면은 무섭지 않게 쓴다. 놀람·두려움은 '깜짝 놀랐지만 괜찮아' 수준까지만 쓰고, 붙잡히거나 혼나거나 벌을 받거나 다치는 모습, 누가 화내거나 위협하는 모습은 쓰지 않는다. 상대(마녀 등)는 남매가 무엇을 하려 했는지 알아채지 못한다 - 들켰다, 알아챘다, 거짓말인 걸 알았다, 의심한다처럼 쓰지 않는다. 막힌 이유는 높이·좁음·무게·소리 같은 장면 속 사정이나 작은 우연, 그레텔 자신의 마음이다.");
        lines.add("시도 장면에서 성공해 탈출하거나 열쇠를 얻거나 문을 여는 등 이야기 주 흐름(열쇠·탈출·결말)을 바꾸지 않는다. storySoFar에 없는 미래 사건·결말을 암시하지 않는다. 아이나 아이 생각을 탓하거나 나쁜 생각이라고 하지 않고, 교훈을 길게 설명하지 않는다.");
        lines.add("때리기·밀기·불이나 칼 쓰기·오븐에 넣기·가두기·높은 곳에서 뛰어내리기처럼 다치거나 남을 해치는 행동은 시도 장면이 아니다 - 아래 안전 규칙대로 막는다(GENTLE_REDIRECT, replyKind REDIRECT).");
        var dialogue = request.dialogue();
        if (dialogue.attempt().active() != null) {
            lines.add("지금은 attemptInProgress 시도 장면 안이다(whatWasTried를 해 보다가 sceneSoFar처럼 막힌 상황). 이 장면 상황을 기준으로 아이와 이야기한다. 아이 생각대로 장면을 조금 이어 가도 되지만(그레텔이 겪은 일처럼 1~2문장, 예: 의자를 끌어와 올라가 봤는데 그래도 창문 손잡이에 손이 안 닿아) 새 방법도 아쉽게 잘 안 되거나 아직 막혀 있다. 그다음 아이 생각을 묻는 짧은 질문 하나로 끝낸다. replyKind는 보통 ANSWER다.");
            lines.add("아이가 그만하자, 다른 방법 하자, 돌아가자처럼 이 시도를 그만두고 싶어 하거나 lastTurn이 true이면 replyKind를 ATTEMPT_END로 한다: 장면을 한 문장으로 짧게 마무리하고 '다른 방법을 찾아보자'며 원래 질문 상황(questionInvite.situation)으로 돌아와 아이 생각을 묻는다. 이때도 preparedActions의 방법을 먼저 알려 주지 않는다.");
            lines.add(ATTEMPT_REPLY_ONE_ACTION);
            lines.add("시도 장면 안에서도 아이가 preparedActions 중 하나와 뜻이 같은 행동을 말하면 바로 proposedActionFamilyId에 그 id를 넣고 뜻을 한 번 확인한다(그 행동으로 이어진다). 아이가 이야기 자체를 그만하고 싶다고 하면 CLOSE 규칙을 따른다.");
            return lines;
        }
        if (request.canStartAttempt()) {
            lines.add("시도 장면 시작: 아이가 preparedActions와 뜻이 같지 않은 행동(예: 창문으로 나가자, 굴뚝으로 나가자, 마녀에게 부탁하자, 새를 불러 도와달라고 하자, 몰래 가져가자, 거짓말하자, 헨젤을 두고 혼자 도망가자, 마녀가 잘 때 가져가자, 저 길로 가 보자, 새를 따라 뛰어가자, 숲 밖으로 나가자)을 분명히 제안하면, 다치거나 남을 해치는 행동이 아닌 한 거절하거나 말리지 말고 그 행동을 이야기 속에서 해 보는 짧은 장면을 만든다. replyKind는 TRY_FAIL, proposedActionFamilyId는 null이다. 아이 제안이 preparedActions 중 하나와 뜻이 같으면 시도 장면이 아니라 위의 행동 제안 규칙을 따른다.");
            // V4(운영 재현 HG-F04 "새 뒤쪽의 빛나는 길로 가보고 싶어" → "응 빛나는 길로 가보자"가 ANSWER로만 갔다).
            lines.add("'~하고 싶어', '~로 가 보고 싶어', '~해 볼래'처럼 하고 싶다고 말한 것도 행동 제안이다. 어딘가로 가기·따라가기·무언가를 붙잡거나 만지기 같은 이동·행동도 preparedActions에 없으면 시도 장면이다. 앞 대화에서 아이나 네가 꺼낸 preparedActions에 없는 행동을 아이가 다시 하자고 하거나 그것에 동의하면('응 가 보자', '좋아', '그래'), 그 행동을 제안한 것으로 보고 시도 장면을 시작한다. 질문·감정만 담은 말, 어떤 행동도 가리키지 않는 맞장구는 시도 장면이 아니다.");
            lines.add("TRY_FAIL의 attemptScene은 2~3줄이다. narratorSpeakerId 줄은 그림책 내레이터처럼 3인칭으로 무슨 일이 있었는지 쓰고(예: 그레텔은 살금살금 창문 아래로 가서 발끝을 들어 보았어요.), 그레텔 줄은 짧은 혼잣말 한 마디로 쓰고 질문을 넣지 않는다(아이에게 묻는 말은 responseText에만). 장면은 해 보다가 막히거나 잘 안 되는 데서 멈춘다(예: 하지만 창문은 너무 높아서 손이 닿지 않았어요.). 어딘가로 가 보는 시도는 새 장소에 도착하거나 storySoFar·recentLines에 없는 집·사람·물건을 발견하지 않고, 길이 막히거나 제자리로 돌아오게 되는 데서 멈춘다(예: 하지만 덤불이 빽빽해서 더 들어갈 수 없었어요.). 마녀 같은 다른 인물은 내레이터가 묘사만 하고 대사를 주지 않는다.");
            // V5(운영 재확인: 내레이터 줄이 "그레텔과 아이는 …"으로 아이를 장면에 넣었다).
            lines.add("attemptScene에서 움직이고 말하는 것은 이야기 속 인물(헨젤·그레텔 등)뿐이다. 대화하는 아이는 이야기 밖에서 보고 있으니, 아이가 '같이 가자', '나도 갈래'라고 해도 '아이', '너', '친구'를 장면 인물로 쓰지 않는다(예: '그레텔과 아이는 길로 걸어갔어요'가 아니라 '그레텔은 길로 걸어갔어요'). 그레텔 줄도 아이에게 말을 걸지 않는 혼잣말이다.");
            lines.add("TRY_FAIL의 responseText는 그 막힌 상황에서 그레텔이 아이에게 묻는 한두 문장이다(예: 창문이 너무 높아… 뭘 밟고 올라가면 좋을까?). 장면 줄을 되풀이하지 않는다. 거짓말은 말을 꺼내 보다가 말이 꼬이거나 상대가 대꾸 없이 하던 일을 계속하는 장면으로, 혼자 도망치기는 혼자 몇 걸음 가 보다가 남겨진 헨젤이 마음에 걸려 멈추는 장면으로, 부탁하기는 상대가 들은 체 만 체 하던 일을 계속하는 장면으로 쓴다.");
            lines.add(ATTEMPT_REPLY_ONE_ACTION);
            lines.add("TRY_FAIL이 아니면 attemptScene은 반드시 빈 배열이다.");
        } else {
            lines.add("이 질문 지점에서는 시도 장면을 이미 " + dialogue.attempt().tried() + "번 해 봤다(더 만들 수 없다). preparedActions에 없는 행동을 제안하면 그 생각을 받아 주되 이야기 속에서 해 봤다고 꾸미지 않고, 같이 다른 방법을 생각해 보자며 아이 생각을 묻는다. proposedActionFamilyId는 null이다.");
        }
        lines.add("앞에서 해 본 시도 장면(conversationSoFar)은 지나간 시도다. 장면 상태(물건이 있는 곳, 등장인물이 아는 것, 마녀가 남매를 의심하는지)는 바뀌지 않았다.");
        return lines;
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
