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
 * Java port of the validation/re-authoring pipeline in providers/openrouter.mjs: the server never
 * trusts the LLM's JSON blindly, and re-derives THREE_PATHS/agency-choice/concern-choice routes
 * from fixed, reviewed story data - by re-running the *same* validator against the reconstructed
 * candidate, exactly like Node's validateRouteResult(...) call inside each promote/align helper.
 * Order matters: alignActionRouteCoverage runs before promoteConcernToChoice, which runs before
 * guaranteeBetaAgencyChoice, exactly as in OpenRouterClient.generatePlan().
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

    /** Raw candidate fields, mirroring the plain object literals each Node helper builds before re-validating. */
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
                    option.path("actionFamilyId").asText("").trim()));
        }
        return raw;
    }

    /** The single validation core every entry point (LLM output and internally-reconstructed candidates) funnels through. */
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
                    family != null ? family.meaning() : "안전한 방법을 확인한다.", familyId));
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
                    family != null ? family.meaning() : "안전한 다음 행동을 고른다.", familyIds.get(i)));
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
                    family != null ? family.meaning() : "지금 장면에서 할 수 있는 방법을 살펴본다.", familyIds.get(i)));
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

    private static Map<String, ActionFamily> familyMap(StoryContext storyContext) {
        Map<String, ActionFamily> map = new LinkedHashMap<>();
        storyContext.actionFamilies().forEach(family -> map.put(family.id(), family));
        return map;
    }
}
