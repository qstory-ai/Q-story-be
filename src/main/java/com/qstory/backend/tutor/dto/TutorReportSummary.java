package com.qstory.backend.tutor.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.UUID;

/**
 * 부모가 "수업 리포트" 목록에서 보는 항목. studentName은 이 부모의 아이 중 참여한 학생 이름(둘 이상이면 쉼표로),
 * sessionKind가 CLASS면 className 반이 함께 읽은 기록이다.
 */
public record TutorReportSummary(
        UUID id,
        String storyId,
        Instant completedAt,
        Integer durationSeconds,
        String studentName,
        String tutorDisplayName,
        String sessionKind,
        String className,
        String organizationName) {

    public static TutorReportSummary of(StoryCompletion completion, String studentName) {
        return new TutorReportSummary(
                completion.getId(), completion.getStoryId(), completion.getCompletedAt(), completion.getDurationSeconds(),
                studentName,
                completion.getUser().getDisplayName(),
                completion.sessionKind(),
                completion.getClassGroup() == null ? null : completion.getClassGroup().getName(),
                completion.getOrganization() == null ? null : completion.getOrganization().getName());
    }
}
