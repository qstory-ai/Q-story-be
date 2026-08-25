package com.qstory.backend.question.dto;

import com.qstory.backend.provider.openrouter.RouteDecision;
import com.qstory.backend.provider.openrouter.RouteOption;
import java.util.List;

/** 확정된 질문 답변의 전송 형태(wire shape). 필드명은 src/core/contracts/speech.ts의 RoutePlan과
 * 정확히 일치하도록 RouteDecision에서 이름을 바꾼 것이다 (rejoinAnchorId -> rejoinAt, responseText -> text). */
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
