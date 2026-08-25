package com.qstory.backend.provider.openrouter.util;
import com.qstory.backend.provider.openrouter.RouteOption;
import com.qstory.backend.provider.openrouter.RouteDecision;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.choicecopy.service.ChoiceCopyService;
import com.qstory.backend.common.enums.CoverageStatus;
import com.qstory.backend.common.enums.RouteKind;
import com.qstory.backend.story.ActionFamily;
import com.qstory.backend.story.ConcernChoice;
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
 * providers/openrouter.mjs에 있는 검증/재작성(validation/re-authoring) 파이프라인을 Java로 포팅한 것: 서버는
 * LLM이 반환한 JSON을 절대 그대로 신뢰하지 않으며, THREE_PATHS/agency-choice/concern-choice 라우트를
 * 고정되고 검수된 스토리 데이터로부터 다시 도출한다 - 재구성된 후보(candidate)에 대해 *동일한* validator를
 * 다시 실행함으로써, 각 promote/align 헬퍼 내부에서 Node의 validateRouteResult(...) 호출과 정확히 동일하게 동작한다.
 * 순서가 중요하다: alignActionRouteCoverage는 promoteConcernToChoice보다 먼저 실행되고, promoteConcernToChoice는
 * guaranteeBetaAgencyChoice보다 먼저 실행되며, 이는 OpenRouterClient.generatePlan()에서와 정확히 동일하다.
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
            "SKIP_CONTINUE", 60);

    private static final List<String[]> RESPONSE_TEXT_CORRECTIONS = List.of(
            new String[] {"다가지지", "다가가지"},
            new String[] {"살쳐보", "살펴보"},
            new String[] {"날개를 날개짓하", "날갯짓하"});

    private static final Pattern CONCERN_CHOICE_CUE = Pattern.compile(
            "(걱정|불안|무서|수상|의심|믿어도|괜찮|안전|위험|따라가도|들어가도|가까이\\s*가도)");

    /**
     * 자유롭게 생성된 THREE_PATHS 옵션 문구에 대한 최선의(best-effort) 안전망이다(sanitizeGeneratedOptionCopy 참고)
     * - 결코 주된 안전 장치는 아닌데, 생성 프롬프트가 이미 모든 옵션을 사전에 작성되고 검수된 소수의
     * actionFamilyIds 집합과 고정된 rejoin 지점 중 하나로 제한하고 있기 때문이다. 이 패턴은 어떤 family에
     * 붙어 있는지와 무관하게, 문구 자체만으로 안전하지 않아 보이는 것을 잡아낼 뿐이다.
     */
    private static final Pattern UNSAFE_GENERATED_OPTION_CUE = Pattern.compile(
            "(칼|피\\s*흘|죽는다|죽였|다친다|폭력|때리|무기|불[이을]\\s*지르|납치)");

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

    public RouteDecision validateRouteResult(JsonNode value, StoryContext storyContext, String modelId) {
        if (value == null || !value.isObject() || storyContext == null) {
            return null;
        }
        String route = value.path("route").asText("").trim();
        if (!RouteKind.ALL_NAMES.contains(route)) {
            return null;
        }
        String responseText = value.path("responseText").isTextual() ? value.get("responseText").asText().trim() : "";
        String childRelevantMeaning = value.path("childRelevantMeaning").asText("").trim();
        String coverageStatus = value.path("coverageStatus").isTextual()
                ? value.get("coverageStatus").asText().trim()
                : CoverageStatus.EXACT.wireValue();
        String coverageReason = value.path("coverageReason").isTextual()
                ? value.get("coverageReason").asText().trim()
                : "검수된 아이 응답 경로로 처리할 수 있다.";
        String speakerId = value.path("speakerId").asText("").trim();
        NullableString actionFamilyId = NullableString.of(value.get("actionFamilyId"));
        NullableString rejoinAnchorId = NullableString.of(value.get("rejoinAnchorId"));
        NullableString fallbackFamilyId = NullableString.of(value.get("fallbackFamilyId"));
        if (!actionFamilyId.valid() || !rejoinAnchorId.valid() || !fallbackFamilyId.valid()) {
            return null;
        }
        List<RouteOption> options = readOptions(value.get("options"));
        if (options == null) {
            return null;
        }
        return validate(
                new Candidate(
                        route, childRelevantMeaning, coverageStatus, coverageReason, responseText, speakerId,
                        actionFamilyId.value(), rejoinAnchorId.value(), fallbackFamilyId.value(), options),
                storyContext, modelId);
    }

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

    /** 모든 진입점(LLM 출력과 내부적으로 재구성된 후보 모두)이 거쳐 가는 단일한 검증 핵심 로직. */
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

        if (RouteKind.SIMPLE_ROUTES.contains(route)
                && (actionFamilyId != null || rejoinAnchorId != null || fallbackFamilyId != null || !options.isEmpty())) {
            return null;
        }
        if (RouteKind.ACTION_ROUTES.contains(route)
                && (actionFamilyId == null || !allowedFamilyIds.contains(actionFamilyId)
                        || !storyContext.rejoinAt().equals(rejoinAnchorId)
                        || !storyContext.fallbackFamilyId().equals(fallbackFamilyId)
                        || !options.isEmpty())) {
            return null;
        }
        if (route.equals("THREE_PATHS")
                && (actionFamilyId != null
                        || !storyContext.rejoinAt().equals(rejoinAnchorId)
                        || !storyContext.fallbackFamilyId().equals(fallbackFamilyId)
                        || options.size() != 3)) {
            return null;
        }

        return new RouteDecision(
                route, childRelevantMeaning, coverageStatus, coverageReason, responseText, candidate.speakerId(),
                actionFamilyId, rejoinAnchorId, fallbackFamilyId, options, modelId, storyContext.versions());
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

    public RouteDecision promoteConcernToChoice(
            RouteDecision routeResult, StoryContext storyContext, String transcript, int questionRound) {
        ConcernChoice concernChoice = storyContext.concernChoice();
        if (routeResult == null || !"ANSWER_RESUME".equals(routeResult.route())
                || concernChoice == null || !CONCERN_CHOICE_CUE.matcher(transcript).find()) {
            return routeResult;
        }
        Map<String, ActionFamily> familyById = familyMap(storyContext);
        List<RouteOption> options = new ArrayList<>();
        for (int i = 0; i < concernChoice.familyIds().size(); i++) {
            String familyId = concernChoice.familyIds().get(i);
            ActionFamily family = familyById.get(familyId);
            options.add(new RouteOption(
                    "OPTION_" + (i + 1), "안전한 방법 " + (i + 1),
                    family != null ? family.meaning() : "안전한 방법을 확인한다.", familyId,
                    family != null ? family.acknowledgementText() : "좋아, 안전하게 확인해보자."));
        }
        RouteDecision promoted = validate(
                new Candidate(
                        "THREE_PATHS", routeResult.childRelevantMeaning(), routeResult.coverageStatus(),
                        routeResult.coverageReason(), concernChoice.responseText(), routeResult.speakerId(),
                        null, storyContext.rejoinAt(), storyContext.fallbackFamilyId(), options),
                storyContext, routeResult.modelId());
        if (promoted == null) {
            return routeResult;
        }
        return promoted.withOptions(choiceCopyService.authoredChoiceOptions(promoted.options(), transcript, questionRound));
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

    public RouteDecision alignActionRouteCoverage(
            RouteDecision routeResult, StoryContext storyContext, String transcript, int questionRound) {
        if (routeResult == null || !RouteKind.ACTION_ROUTES.contains(routeResult.route())
                || CoverageStatus.EXACT.wireValue().equals(routeResult.coverageStatus()) || storyContext == null) {
            return routeResult;
        }
        LinkedHashSet<String> preferred = new LinkedHashSet<>();
        if (routeResult.actionFamilyId() != null) {
            preferred.add(routeResult.actionFamilyId());
        }
        if (storyContext.concernChoice() != null) {
            preferred.addAll(storyContext.concernChoice().familyIds());
        }
        preferred.addAll(storyContext.actionFamilyIds());
        List<String> familyIds = preferred.stream().limit(3).toList();
        if (familyIds.size() < 3) {
            return routeResult;
        }
        Map<String, ActionFamily> familyById = familyMap(storyContext);
        List<RouteOption> options = new ArrayList<>();
        for (int i = 0; i < familyIds.size(); i++) {
            ActionFamily family = familyById.get(familyIds.get(i));
            options.add(new RouteOption(
                    "OPTION_" + (i + 1), "이야기 길 " + (i + 1),
                    family != null ? family.meaning() : "지금 장면에서 할 수 있는 방법을 살펴본다.", familyIds.get(i),
                    family != null ? family.acknowledgementText() : "좋아, 지금 할 수 있는 방법을 살펴보자."));
        }
        RouteDecision aligned = validate(
                new Candidate(
                        "THREE_PATHS", routeResult.childRelevantMeaning(), routeResult.coverageStatus(),
                        routeResult.coverageReason(),
                        "그 생각을 그대로 하기보다, 지금 장면과 자연스럽게 이어지는 세 방법 중에서 골라 볼까?",
                        routeResult.speakerId(), null, storyContext.rejoinAt(), storyContext.fallbackFamilyId(), options),
                storyContext, routeResult.modelId());
        if (aligned == null) {
            return routeResult;
        }
        return aligned.withOptions(choiceCopyService.authoredChoiceOptions(aligned.options(), transcript, questionRound));
    }

    /**
     * LLM이 직접 생성한 THREE_PATHS 옵션 문구를, 항상 ChoiceCopyService의 사전 작성된 변형(variant)으로
     * 덮어쓰는 대신 아이에게 그대로 도달하게 해준다(OpenRouterClient.generatePlan()의 최종 반환값 참고).
     * 옵션 단위로, 오직 규칙 기반(rules-based)으로만 검사한다 - 두 번째 LLM 판정 호출은 없는데, 생성 표면이
     * 이미 촘촘히 제한되어 있기 때문이다(고정된 rejoinAnchorId/fallbackFamilyId, 이 앵커에서 허용된 family로
     * 제한된 actionFamilyId, 유일한 창작 여지로서의 family.meaning()). 검사에 실패한 옵션은 해당 family에 대한
     * 기존의 작성된 문구로 대체된다 - 절대 막지 않고, 절대 옵션을 누락시키지도 않는다.
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
