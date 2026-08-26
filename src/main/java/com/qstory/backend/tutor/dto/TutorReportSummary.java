package com.qstory.backend.tutor.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.UUID;

/** 부모가 "선생님에게 받은 기록" 목록에서 보는 항목 - 어느 선생님·어느 학생과 진행한 세션인지 함께 담는다. */
public record TutorReportSummary(
        UUID id, String storyId, Instant completedAt, Integer durationSeconds, String studentName, String tutorDisplayName) {

    public static TutorReportSummary of(StoryCompletion completion) {
        return new TutorReportSummary(
                completion.getId(), completion.getStoryId(), completion.getCompletedAt(), completion.getDurationSeconds(),
                completion.getTutorStudent().getName(), completion.getTutorStudent().getTutor().getDisplayName());
    }
}
