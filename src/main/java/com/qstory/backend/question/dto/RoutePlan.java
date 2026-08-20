package com.qstory.backend.question.dto;

import com.qstory.backend.provider.openrouter.RouteDecision;
import com.qstory.backend.provider.openrouter.RouteOption;
import java.util.List;

/** Wire shape for a resolved question answer. Field names are renamed from RouteDecision to match
 * src/core/contracts/speech.ts's RoutePlan exactly (rejoinAnchorId -> rejoinAt, responseText -> text). */
public record RoutePlan(
        String kind,
        String route,
        String childRelevantMeaning,
        String coverageStatus,
        String coverageReason,
        String text,
        String speakerId,
        String actionFamilyId,
        String rejoinAt,
        String fallbackFamilyId,
        List<RouteOption> options,
        PlanVersions versions) {

    public static RoutePlan of(RouteDecision decision) {
        return new RoutePlan(
                "route", decision.route(), decision.childRelevantMeaning(), decision.coverageStatus(),
                decision.coverageReason(), decision.responseText(), decision.speakerId(), decision.actionFamilyId(),
                decision.rejoinAnchorId(), decision.fallbackFamilyId(), decision.options(),
                PlanVersions.of(decision.modelId(), decision.storyVersions()));
    }
}
