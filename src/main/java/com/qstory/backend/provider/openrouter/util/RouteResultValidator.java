package com.qstory.backend.provider.openrouter.util;
import com.qstory.backend.provider.openrouter.ContentGeneration;
import com.qstory.backend.provider.openrouter.RouteClassification;
import com.qstory.backend.provider.openrouter.RouteOption;
import com.qstory.backend.provider.openrouter.RouteDecision;
import com.qstory.backend.provider.openrouter.SafetyVerdict;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.choicecopy.service.ChoiceCopyService;
import com.qstory.backend.common.enums.CoverageStatus;
import com.qstory.backend.common.enums.RouteKind;
import com.qstory.backend.story.ActionFamily;
import com.qstory.backend.story.StoryContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 서버는 LLM이 반환한 JSON을 절대 그대로 신뢰하지 않는다. Phase 2부터는 하나의 거대한 스키마 대신
 * 3단계 파이프라인(safety_scope_gate -> route_classifier -> content_generator)이 각자 자기 몫만
 * 반환하므로, 이 클래스도 단계별로 나뉜 검증 메서드({@link #validateSafetyVerdict},
 * {@link #validateClassification}, {@link #validateContent})를 제공한다 - 그리고 최종 조립된
 * RouteDecision에 대해서는 여전히 {@link #guaranteeBetaAgencyChoice}/{@link #sanitizeGeneratedOptionCopy}만
 * 그대로 적용한다(QuestionRoutingService 참고).
 *
 * <p>예전에 있던 alignActionRouteCoverage/promoteConcernToChoice(단일 호출 시절 "커버 안 됨 -&gt; 기존
 * family로 억지 THREE_PATHS" 보정)는 폐기됐다 - 이제는 분류기 자신이 NEW_CHOICES/coverageStatus로
 * 그 판단을 내리기 때문이다(계획 문서 Phase 2 §1 참고). concern-choice 기반 강제 선택지
 * (guaranteeBetaAgencyChoice)는 그 폐기 대상과 무관한 별개의 비즈니스 규칙이라 그대로 남는다.
 */
@Component
public class RouteResultValidator {

    private static final Map<String, Integer> RESPONSE_LIMITS = Map.of(
            "ANSWER_RESUME", 120,
            "DIRECT_ACTION", 60,
            "THREE_PATHS", 120,
            "SCENE_REPLACE", 120,
            "DETOUR_REJOIN", 120,
            "CLARIFY_ONCE", 60,
            "GENTLE_REDIRECT", 120,
            "SKIP_CONTINUE", 60,
            // NEW_CHOICES의 실제 responseText는 stage3를 거치지 않고 QuestionRoutingService가 고정
            // 안내 문구로 채운다 - 이 한도는 그 문구 자체가 아니라 방어적 상한으로만 존재한다.
            "NEW_CHOICES", 120);

    private static final List<String[]> RESPONSE_TEXT_CORRECTIONS = List.of(
            new String[] {"다가지지", "다가가지"},
            new String[] {"살쳐보", "살펴보"},
            new String[] {"날개를 날개짓하", "날갯짓하"});

    /**
     * 자유롭게 생성된 THREE_PATHS 옵션 문구에 대한 최선의(best-effort) 안전망이다(sanitizeGeneratedOptionCopy 참고)
     * - 결코 주된 안전 장치는 아닌데, 생성 프롬프트가 이미 모든 옵션을 사전에 작성되고 검수된 소수의
     * actionFamilyIds 집합과 고정된 rejoin 지점 중 하나로 제한하고 있기 때문이다. 이 패턴은 어떤 family에
     * 붙어 있는지와 무관하게, 문구 자체만으로 안전하지 않아 보이는 것을 잡아낼 뿐이다.
     */
    private static final Pattern UNSAFE_GENERATED_OPTION_CUE = Pattern.compile(
            "(칼|피\\s*흘|죽는다|죽였|다친다|폭력|때리|무기|불[이을]\\s*지르|납치)");

    private static final Set<String> SAFETY_VERDICTS = Set.of("PASS", "REDIRECT");

    private final ChoiceCopyService choiceCopyService;

    public RouteResultValidator(ChoiceCopyService choiceCopyService) {
        this.choiceCopyService = choiceCopyService;
    }

    public String normalizeKoreanResponseText(String value) {
        String text = value;
        for (String[] correction : RESPONSE_TEXT_CORRECTIONS) {
            text = text.replace(correction[0], correction[1]);
        }
        return text;
    }

    /** 각 Node 헬퍼가 재검증(re-validating)하기 전에 만드는 평범한 객체 리터럴을 그대로 반영한, 가공되지 않은 후보 필드들. */
    private record Candidate(
            String route,
            String childRelevantMeaning,
            String coverageStatusRaw,
            String coverageReasonRaw,
            String responseText,
            String speakerId,
            String actionFamilyId,
            String rejoinAnchorId,
            String fallbackFamilyId,
            List<RouteOption> options) {}

    private record NullableString(boolean valid, String value) {
        static final NullableString INVALID = new NullableString(false, null);

        static NullableString of(JsonNode node) {
            if (node == null || node.isMissingNode()) {
                return INVALID;
            }
            if (node.isNull()) {
                return new NullableString(true, null);
            }
            return node.isTextual() ? new NullableString(true, node.asText().trim()) : INVALID;
        }
    }

    /**
     * 1단계 safety_scope_gate의 출력 검증. verdict가 REDIRECT일 때만 redirectReason/responseText가
     * 채워져 있어야 하고, PASS일 때는 둘 다 null이어야 한다(모델이 실수로 채워 보내도 여기서 지운다 -
     * QuestionRoutingService가 REDIRECT 여부만으로 분기하므로 PASS 경로에서는 이 필드들을 쓰지 않는다).
     */
    public SafetyVerdict validateSafetyVerdict(JsonNode value, String modelId) {
        if (value == null || !value.isObject()) {
            return null;
        }
        String verdict = value.path("verdict").asText("").trim();
        if (!SAFETY_VERDICTS.contains(verdict)) {
            return null;
        }
        if (!"REDIRECT".equals(verdict)) {
            return new SafetyVerdict("PASS", null, null, modelId);
        }
        String redirectReason = value.path("redirectReason").isTextual() ? value.get("redirectReason").asText().trim() : "";
        String responseText = value.path("responseText").isTextual() ? value.get("responseText").asText().trim() : "";
        if (redirectReason.isEmpty() || redirectReason.length() > 160
                || responseText.isEmpty() || responseText.length() > RESPONSE_LIMITS.get("GENTLE_REDIRECT")) {
            return null;
        }
        return new SafetyVerdict("REDIRECT", redirectReason, normalizeKoreanResponseText(responseText), modelId);
    }

    /**
     * 2단계 route_classifier의 출력 검증. GENTLE_REDIRECT는 이 단계가 절대 반환할 수 없는 route다
     * (안전 판정은 1단계 전담, RouteKind.CLASSIFIER_ROUTES 참고). actionFamilyId/rejoinAnchorId/
     * fallbackFamilyId의 null 여부 규칙은 오늘의 단일 호출 스키마와 동일하다 - 단순 route와
     * NEW_CHOICES는 셋 다 null, 행동 route는 셋 다 채워짐(고정된 rejoin/fallback과 허용 family 하나),
     * THREE_PATHS는 actionFamilyId만 null.
     */
    public RouteClassification validateClassification(JsonNode value, StoryContext storyContext, String modelId) {
        if (value == null || !value.isObject() || storyContext == null) {
            return null;
        }
        String route = value.path("route").asText("").trim();
        if (!RouteKind.CLASSIFIER_ROUTES.contains(route)) {
            return null;
        }
        String matchedGate = value.path("matchedGate").asText("").trim();
        if (matchedGate.isEmpty() || matchedGate.length() > 8) {
            return null;
        }
        String coverageStatus = value.path("coverageStatus").asText("").trim();
        if (!CoverageStatus.WIRE_VALUES.contains(coverageStatus)) {
            return null;
        }
        String coverageReason = value.path("coverageReason").asText("").trim();
        String childRelevantMeaning = value.path("childRelevantMeaning").asText("").trim();
        if (coverageReason.isEmpty() || coverageReason.length() > 160
                || childRelevantMeaning.isEmpty() || childRelevantMeaning.length() > 160) {
            return null;
        }
        String speakerId = value.path("speakerId").asText("").trim();
        if (!storyContext.allowedSpeakerIds().contains(speakerId)) {
            return null;
        }
        NullableString actionFamilyId = NullableString.of(value.get("actionFamilyId"));
        NullableString rejoinAnchorId = NullableString.of(value.get("rejoinAnchorId"));
        NullableString fallbackFamilyId = NullableString.of(value.get("fallbackFamilyId"));
        if (!actionFamilyId.valid() || !rejoinAnchorId.valid() || !fallbackFamilyId.valid()) {
            return null;
        }

        Set<String> allowedFamilyIds = new LinkedHashSet<>(storyContext.actionFamilyIds());
        if (!familyFieldsValid(
                route, actionFamilyId.value(), rejoinAnchorId.value(), fallbackFamilyId.value(),
                storyContext, allowedFamilyIds)) {
            return null;
        }
        return new RouteClassification(
                route, matchedGate, coverageStatus, coverageReason, childRelevantMeaning,
                actionFamilyId.value(), rejoinAnchorId.value(), fallbackFamilyId.value(), speakerId, modelId);
    }

    /**
     * 3단계 content_generator의 출력 검증. optionSlots는 호출자(OpenRouterClient.generateContent)가
     * 이미 확정한 route로부터 결정한 값(THREE_PATHS만 3, 그 외 0)이다 - JSON 스키마 자체의
     * minItems/maxItems로도 강제하지만, 모델이 그래도 개수를 틀리게 반환할 수 있으므로 여기서 다시
     * 확인한다(계획 문서가 명시한 "옵션 개수는 스키마만으로 강제할 수 없다" 우려에 대한 방어).
     */
    public ContentGeneration validateContent(JsonNode value, StoryContext storyContext, String route, int optionSlots) {
        if (value == null || !value.isObject() || storyContext == null) {
            return null;
        }
        String responseText = value.path("responseText").isTextual() ? value.get("responseText").asText().trim() : "";
        int limit = RESPONSE_LIMITS.getOrDefault(route, 160);
        if (responseText.isEmpty() || responseText.length() > limit) {
            return null;
        }
        List<RouteOption> options = readOptions(value.get("options"));
        if (options == null || options.size() != optionSlots) {
            return null;
        }
        if (optionSlots > 0) {
            Set<String> allowedFamilyIds = new LinkedHashSet<>(storyContext.actionFamilyIds());
            options = validateOptions(options, allowedFamilyIds);
            if (options == null) {
                return null;
            }
        }
        return new ContentGeneration(normalizeKoreanResponseText(responseText), options);
    }

    private List<RouteOption> readOptions(JsonNode optionsNode) {
        if (optionsNode == null || !optionsNode.isArray()) {
            return null;
        }
        List<RouteOption> raw = new ArrayList<>();
        for (JsonNode option : optionsNode) {
            if (!option.isObject()) {
                return null;
            }
            raw.add(new RouteOption(
                    option.path("id").asText("").trim(),
                    option.path("label").asText("").trim(),
                    option.path("meaning").asText("").trim(),
                    option.path("actionFamilyId").asText("").trim(),
                    option.path("branchLine").asText("").trim()));
        }
        return raw;
    }

    private List<RouteOption> validateOptions(List<RouteOption> options, Set<String> allowedFamilyIds) {
        if (options == null) {
            return null;
        }
        List<RouteOption> normalized = new ArrayList<>();
        for (int index = 0; index < options.size(); index++) {
            RouteOption option = options.get(index);
            if (!option.id().equals("OPTION_" + (index + 1))
                    || option.label().isEmpty() || option.label().length() > 18
                    || option.meaning().isEmpty() || option.meaning().length() > 120
                    || option.branchLine().isEmpty() || option.branchLine().length() > 60
                    || !allowedFamilyIds.contains(option.actionFamilyId())) {
                return null;
            }
            normalized.add(option);
        }
        if (new LinkedHashSet<>(normalized.stream().map(RouteOption::label).toList()).size() != normalized.size()
                || new LinkedHashSet<>(normalized.stream().map(RouteOption::actionFamilyId).toList()).size()
                        != normalized.size()) {
            return null;
        }
        return normalized;
    }

    /**
     * route 카테고리별 actionFamilyId/rejoinAnchorId/fallbackFamilyId nullability 규칙 -
     * validateClassification()(stage2)과 validate()(레거시 후보 재검증)이 공유한다. options 관련
     * 규칙(SIMPLE_ROUTES/ACTION_ROUTES는 빈 배열, THREE_PATHS는 3개)은 stage2 출력에는 options
     * 필드 자체가 없으므로 이 헬퍼에 넣지 않고 각 호출부가 따로 처리한다.
     */
    private boolean familyFieldsValid(
            String route, String actionFamilyId, String rejoinAnchorId, String fallbackFamilyId,
            StoryContext storyContext, Set<String> allowedFamilyIds) {
        if (RouteKind.SIMPLE_ROUTES.contains(route) || RouteKind.NEW_CONTENT_ROUTES.contains(route)) {
            return actionFamilyId == null && rejoinAnchorId == null && fallbackFamilyId == null;
        }
        if (RouteKind.ACTION_ROUTES.contains(route)) {
            return actionFamilyId != null && allowedFamilyIds.contains(actionFamilyId)
                    && storyContext.rejoinAt().equals(rejoinAnchorId)
                    && storyContext.fallbackFamilyId().equals(fallbackFamilyId);
        }
        if ("THREE_PATHS".equals(route)) {
            return actionFamilyId == null
                    && storyContext.rejoinAt().equals(rejoinAnchorId)
                    && storyContext.fallbackFamilyId().equals(fallbackFamilyId);
        }
        return true;
    }

    /** 모든 재구성된 THREE_PATHS 후보(guaranteeBetaAgencyChoice)가 거쳐 가는 단일한 검증 핵심 로직. */
    private RouteDecision validate(Candidate candidate, StoryContext storyContext, String modelId) {
        String route = candidate.route();
        if (!RouteKind.ALL_NAMES.contains(route)) {
            return null;
        }
        String responseText = normalizeKoreanResponseText(candidate.responseText());
        String childRelevantMeaning = candidate.childRelevantMeaning();
        String coverageStatus = candidate.coverageStatusRaw();
        String coverageReason = candidate.coverageReasonRaw();
        if (responseText.isEmpty()
                || responseText.length() > RESPONSE_LIMITS.get(route)
                || childRelevantMeaning.isEmpty() || childRelevantMeaning.length() > 160
                || !CoverageStatus.WIRE_VALUES.contains(coverageStatus)
                || coverageReason.isEmpty() || coverageReason.length() > 160) {
            return null;
        }
        if (!storyContext.allowedSpeakerIds().contains(candidate.speakerId())) {
            return null;
        }
        Set<String> allowedFamilyIds = new LinkedHashSet<>(storyContext.actionFamilyIds());
        List<RouteOption> options = validateOptions(candidate.options(), allowedFamilyIds);
        if (options == null) {
            return null;
        }
        String actionFamilyId = candidate.actionFamilyId();
        String rejoinAnchorId = candidate.rejoinAnchorId();
        String fallbackFamilyId = candidate.fallbackFamilyId();

        if (!familyFieldsValid(route, actionFamilyId, rejoinAnchorId, fallbackFamilyId, storyContext, allowedFamilyIds)) {
            return null;
        }
        if ((RouteKind.SIMPLE_ROUTES.contains(route) || RouteKind.ACTION_ROUTES.contains(route))
                && !options.isEmpty()) {
            return null;
        }
        if (route.equals("THREE_PATHS") && options.size() != 3) {
            return null;
        }

        return new RouteDecision(
                route, childRelevantMeaning, coverageStatus, coverageReason, responseText, candidate.speakerId(),
                actionFamilyId, rejoinAnchorId, fallbackFamilyId, options, modelId, storyContext.versions(), null);
    }

    public RouteDecision guaranteeBetaAgencyChoice(
            RouteDecision routeResult, StoryContext storyContext, String transcript, boolean guaranteeAgencyChoice,
            int questionRound) {
        if (!guaranteeAgencyChoice || "A".equals(storyContext.slot())
                || routeResult == null || !"ANSWER_RESUME".equals(routeResult.route())
                || storyContext.concernChoice() == null) {
            return routeResult;
        }
        String invitation = " 이제 다음 행동도 네가 골라 보자.";
        int limit = Math.max(1, RESPONSE_LIMITS.get("THREE_PATHS") - invitation.length());
        String answer = routeResult.responseText().substring(0, Math.min(limit, routeResult.responseText().length()));
        Map<String, ActionFamily> familyById = familyMap(storyContext);
        List<RouteOption> options = new ArrayList<>();
        List<String> familyIds = storyContext.concernChoice().familyIds();
        for (int i = 0; i < familyIds.size(); i++) {
            ActionFamily family = familyById.get(familyIds.get(i));
            options.add(new RouteOption(
                    "OPTION_" + (i + 1), "이야기 길 " + (i + 1),
                    family != null ? family.meaning() : "안전한 다음 행동을 고른다.", familyIds.get(i),
                    family != null ? family.acknowledgementText() : "좋아, 다음 행동을 함께 골라보자."));
        }
        RouteDecision promoted = validate(
                new Candidate(
                        "THREE_PATHS", routeResult.childRelevantMeaning(), routeResult.coverageStatus(),
                        routeResult.coverageReason(), answer + invitation, routeResult.speakerId(),
                        null, storyContext.rejoinAt(), storyContext.fallbackFamilyId(), options),
                storyContext, routeResult.modelId());
        if (promoted == null) {
            return routeResult;
        }
        return promoted.withOptions(choiceCopyService.authoredChoiceOptions(promoted.options(), transcript, questionRound));
    }

    /**
     * LLM이 직접 생성한 THREE_PATHS 옵션 문구를, 항상 ChoiceCopyService의 사전 작성된 변형(variant)으로
     * 덮어쓰는 대신 아이에게 그대로 도달하게 해준다. 옵션 단위로, 오직 규칙 기반(rules-based)으로만
     * 검사한다 - 두 번째 LLM 판정 호출은 없는데, 생성 표면이 이미 촘촘히 제한되어 있기 때문이다(고정된
     * rejoinAnchorId/fallbackFamilyId, 이 앵커에서 허용된 family로 제한된 actionFamilyId, 유일한
     * 창작 여지로서의 family.meaning()). 검사에 실패한 옵션은 해당 family에 대한 기존의 작성된
     * 문구로 대체된다 - 절대 막지 않고, 절대 옵션을 누락시키지도 않는다.
     */
    public RouteDecision sanitizeGeneratedOptionCopy(
            RouteDecision decision, StoryContext storyContext, String transcript, int questionRound) {
        if (decision == null || !"THREE_PATHS".equals(decision.route())) {
            return decision;
        }
        List<RouteOption> options = decision.options();
        List<RouteOption> sanitized = new ArrayList<>();
        boolean anyReplaced = false;
        for (RouteOption option : options) {
            if (isSafeGeneratedOptionCopy(option, storyContext)) {
                sanitized.add(option);
            } else {
                sanitized.add(choiceCopyService.authoredChoiceOptions(List.of(option), transcript, questionRound)
                        .get(0));
                anyReplaced = true;
            }
        }
        return anyReplaced ? decision.withOptions(sanitized) : decision;
    }

    private boolean isSafeGeneratedOptionCopy(RouteOption option, StoryContext storyContext) {
        String combined = option.label() + " " + option.meaning() + " " + option.branchLine();
        for (String forbidden : storyContext.forbiddenKnowledge()) {
            if (forbidden != null && !forbidden.isBlank() && combined.contains(forbidden)) {
                return false;
            }
        }
        return !UNSAFE_GENERATED_OPTION_CUE.matcher(combined).find();
    }

    private static Map<String, ActionFamily> familyMap(StoryContext storyContext) {
        Map<String, ActionFamily> map = new LinkedHashMap<>();
        storyContext.actionFamilies().forEach(family -> map.put(family.id(), family));
        return map;
    }
}
