package com.qstory.backend.story;

import java.util.List;

/**
 * The runtime-resolved view of an Anchor: prerequisite-gated action families/concern choice,
 * plus the identifiers and version stamps the LLM prompt and response validator need. Mirrors
 * story-registry.mjs's normalizeStoryContext() output.
 */
public record StoryContext(
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
        List<ActionFamily> actionFamilies,
        String anchorId,
        String storyId,
        String fallbackFamilyId,
        String rejoinAt,
        StoryVersions versions) {

    public List<String> actionFamilyIds() {
        return actionFamilies.stream().map(ActionFamily::id).toList();
    }
}
