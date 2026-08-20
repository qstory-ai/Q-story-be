package com.qstory.backend.story;

import java.util.List;

public record Anchor(
        String slot,
        String sceneId,
        String summary,
        String primarySpeakerId,
        List<String> allowedSpeakerIds,
        List<String> sttKeywords,
        String defaultFallbackFamilyId,
        String defaultRejoinAt,
        ConcernChoice concernChoice,
        List<String> forbiddenKnowledge,
        List<ActionFamily> actionFamilies) {}
