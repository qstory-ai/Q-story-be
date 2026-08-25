package com.qstory.backend.completionsurvey.dto;

import java.util.List;

/**
 * 완주 후 부모 리포트 화면에서 남기는 "1분 체험 후기" 제출 한 건 - 기존에 외부 Google Form으로
 * 리다이렉트하던 것을 인앱 모달로 대체하며 생긴 계약이다. 문항 구성은 그 Google Form과 동일하다
 * (CompletionSurveyModal.tsx 참고) - 같은 질문을 앱 안에서 받는 것뿐, 새 질문은 아니다.
 */
public record CompletionSurveySubmission(
        String storyId,
        String childAgeBand,
        Integer childEngagement,
        String inputUnderstanding,
        String helpNeeded,
        List<String> childReactions,
        List<String> disruptions,
        Integer reportHelpfulness,
        String bestAspect,
        String topPriority,
        String retryInterest,
        String oneLineReview,
        String reviewUsageConsent,
        String wantsNextStories,
        String contact,
        String contactConsent) {}
