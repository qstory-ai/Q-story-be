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
        StoryVersions storyVersions) {

    public RouteDecision withOptions(List<RouteOption> newOptions) {
        return new RouteDecision(
                route, childRelevantMeaning, coverageStatus, coverageReason, responseText, speakerId,
                actionFamilyId, rejoinAnchorId, fallbackFamilyId, newOptions, modelId, storyVersions);
    }
}
