package com.qstory.backend.story;

import java.util.List;

/**
 * Anchor의 런타임에 해석된 뷰: 선행 조건으로 게이팅된 action families/concern choice와, 그리고
 * LLM 프롬프트 및 응답 검증기가 필요로 하는 식별자와 버전 스탬프들. story-registry.mjs의
 * normalizeStoryContext() 출력을 그대로 반영한다.
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
