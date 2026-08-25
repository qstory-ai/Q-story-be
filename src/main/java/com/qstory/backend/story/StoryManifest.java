package com.qstory.backend.story;

import java.util.Map;

public record StoryManifest(
        String storyId,
        String slug,
        String title,
        String contentVersion,
        String availability,
        String routePromptVersion,
        String routePolicyVersion,
        String responseTextNormalizationVersion,
        Map<String, Anchor> anchors,
        String castVersion,
        Map<String, CastEntry> cast,
        boolean requiresEntitlement,
        String coverImageUrl,
        String description,
        String category) {}
