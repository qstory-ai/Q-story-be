package com.qstory.backend.story;

public record StoryVersions(
        String promptVersion,
        String storyManifestVersion,
        String routePolicyVersion,
        String responseTextNormalizationVersion) {}
