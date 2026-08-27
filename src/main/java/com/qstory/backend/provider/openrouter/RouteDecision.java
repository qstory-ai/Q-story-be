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
        String liveBranchJobId) {

    public RouteDecision withOptions(List<RouteOption> newOptions) {
        return new RouteDecision(
                route, childRelevantMeaning, coverageStatus, coverageReason, responseText, speakerId,
                actionFamilyId, rejoinAnchorId, fallbackFamilyId, newOptions, modelId, storyVersions,
                liveBranchJobId);
    }

    public RouteDecision withLiveBranchJob(String jobId) {
        return new RouteDecision(
                route, childRelevantMeaning, coverageStatus, coverageReason, responseText, speakerId,
                actionFamilyId, rejoinAnchorId, fallbackFamilyId, options, modelId, storyVersions, jobId);
    }
}
