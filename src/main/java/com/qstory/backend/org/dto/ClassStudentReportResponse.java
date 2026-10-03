package com.qstory.backend.org.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.UUID;

/**
 * 반 학생 상세의 리포트 한 줄 - 어느 선생님이 진행한 수업인지 함께 보낸다. 담임이 바뀐 반에서는 관리자가 두
 * 선생님의 기록을 모두 보므로, 기록마다 그때 진행한 선생님(story_completion.user_id)을 붙인다.
 */
public record ClassStudentReportResponse(
        UUID id, String storyId, Instant completedAt, Integer durationSeconds, String sessionKind, UUID lessonId,
        UUID tutorId, String tutorDisplayName) {

    public static ClassStudentReportResponse of(StoryCompletion completion) {
        return new ClassStudentReportResponse(
                completion.getId(), completion.getStoryId(), completion.getCompletedAt(),
                completion.getDurationSeconds(), completion.sessionKind(),
                completion.getLesson() == null ? null : completion.getLesson().getId(),
                completion.getUser().getId(), completion.getUser().getDisplayName());
    }
}
