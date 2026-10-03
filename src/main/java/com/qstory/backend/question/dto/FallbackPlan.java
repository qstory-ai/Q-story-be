package com.qstory.backend.question.dto;

import com.qstory.backend.story.StoryContext;

public record FallbackPlan(String kind, String familyId, String text, String rejoinAt) {

    private static final String FALLBACK_TEXT = "이번에는 말을 정확히 확인하지 못했어. 준비된 이야기로 이어갈게.";

    public static FallbackPlan of(StoryContext storyContext) {
        return new FallbackPlan("fallback", storyContext.fallbackFamilyId(), FALLBACK_TEXT, storyContext.rejoinAt());
    }
}
