package com.qstory.backend.story;

import java.util.Map;

public record Story(
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
        Map<String, CastEntry> cast) {}
