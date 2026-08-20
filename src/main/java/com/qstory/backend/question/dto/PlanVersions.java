package com.qstory.backend.question.dto;

import com.qstory.backend.story.StoryVersions;

public record PlanVersions(
        String modelId,
        String promptVersion,
        String storyManifestVersion,
        String routePolicyVersion,
        String responseTextNormalizationVersion) {

    public static PlanVersions of(String modelId, StoryVersions versions) {
        return new PlanVersions(
                modelId, versions.promptVersion(), versions.storyManifestVersion(),
                versions.routePolicyVersion(), versions.responseTextNormalizationVersion());
    }
}
