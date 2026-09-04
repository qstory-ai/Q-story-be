package com.qstory.backend.provider.openrouter;

import com.qstory.backend.story.StoryVersions;
import java.util.List;

public record RouteDecision(
        String route,
        String childRelevantMeaning,
        String coverageStatus,
        String coverageReason,
        String responseText,
        String speakerId,
        String actionFamilyId,
        String rejoinAnchorId,
        String fallbackFamilyId,
        List<RouteOption> options,
        String modelId,
        StoryVersions storyVersions,
        /** 이 결정이 새 실시간 분기 생성을 트리거했을 때만 채워지는 LiveBranchJob.id (문자열). 그 외에는 null. */
        String liveBranchJobId,
        /** 분류기가 NEW_CHOICES를 골랐지만 앵커당 상한(LiveBranchGenerationService.MAX_LIVE_FAMILIES_PER_ANCHOR)에
         *  걸려 ANSWER_RESUME 폴백으로 강등된 경우에만 true. 프런트는 이 값으로 beta event를 남긴다 -
         *  아이에게 보이는 응답 자체는 여전히 안전한 안내 대사(NEW_CHOICES_CAP_FALLBACK_TEXT)라 UX는
         *  동일하지만, 운영에서 "얼마나 자주 캡에 걸리는가"를 관측해야 상한 값 조정 판단이 가능하다. */
        boolean liveBranchCapped) {

    public RouteDecision withOptions(List<RouteOption> newOptions) {
        return new RouteDecision(
                route, childRelevantMeaning, coverageStatus, coverageReason, responseText, speakerId,
                actionFamilyId, rejoinAnchorId, fallbackFamilyId, newOptions, modelId, storyVersions,
                liveBranchJobId, liveBranchCapped);
    }

    public RouteDecision withLiveBranchJob(String jobId) {
        return new RouteDecision(
                route, childRelevantMeaning, coverageStatus, coverageReason, responseText, speakerId,
                actionFamilyId, rejoinAnchorId, fallbackFamilyId, options, modelId, storyVersions, jobId,
                liveBranchCapped);
    }

    public RouteDecision withLiveBranchCapped() {
        return new RouteDecision(
                route, childRelevantMeaning, coverageStatus, coverageReason, responseText, speakerId,
                actionFamilyId, rejoinAnchorId, fallbackFamilyId, options, modelId, storyVersions,
                liveBranchJobId, true);
    }
}
