package com.qstory.backend.question.dto;

import com.qstory.backend.story.StoryContext;

public record FallbackPlan(String kind, String familyId, String text, String rejoinAt) {

    private static final String FALLBACK_TEXT = "이번에는 말을 정확히 확인하지 못했어. 준비된 이야기로 이어갈게.";

    public static FallbackPlan of(StoryContext storyContext) {
        // 기본 분기가 없는 질문 지점이면 familyId/rejoinAt 모두 null - 클라이언트는 기본 이야기로 이어 간다.
        String familyId = storyContext.fallbackFamilyId();
        return new FallbackPlan("fallback", familyId, FALLBACK_TEXT, familyId == null ? null : storyContext.rejoinAt());
    }
}
