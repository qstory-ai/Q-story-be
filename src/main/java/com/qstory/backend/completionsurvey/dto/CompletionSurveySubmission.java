package com.qstory.backend.completionsurvey.dto;

import java.util.List;

/** 완주 후 부모 리포트 화면에서 남기는 "1분 체험 후기" 제출 한 건(completion-survey-modal.tsx 문항과 1:1). */
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
