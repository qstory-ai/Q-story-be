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
        PlanVersions versions,
        /** decision.liveBranchJobId()의 미러 - 채워져 있으면 프런트가 GET /v1/live-branch/{jobId}를 폴링한다. */
        String liveBranchJobId,
        /** NEW_CHOICES 라우팅이 앵커당 상한에 걸려 조용히 ANSWER_RESUME으로 폴백된 경우 true.
         *  프런트는 이 값이 true면 question_result 이벤트에 result='live_branch_capped'로 남긴다 -
         *  대사 자체는 캡이 아닌 다른 이유로 ANSWER_RESUME이 된 경우와 동일해 UX는 구분되지 않지만,
         *  텔레메트리 상에서는 상한 도달 빈도를 관측할 수 있다. */
        boolean liveBranchCapped) {

    public static RoutePlan of(RouteDecision decision) {
        return new RoutePlan(
                "route", decision.route(), decision.childRelevantMeaning(), decision.coverageStatus(),
                decision.coverageReason(), decision.responseText(), decision.speakerId(), decision.actionFamilyId(),
                decision.rejoinAnchorId(), decision.fallbackFamilyId(), decision.options(),
                PlanVersions.of(decision.modelId(), decision.storyVersions()), decision.liveBranchJobId(),
                decision.liveBranchCapped());
    }
}
