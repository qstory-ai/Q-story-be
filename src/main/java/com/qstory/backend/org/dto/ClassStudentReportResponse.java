package com.qstory.backend.org.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.UUID;

/**
 * 반 학생 상세의 리포트 한 줄 - 어느 선생님이 진행한 수업인지 함께 보낸다. 담임이 바뀐 반에서는 관리자가 두
 * 선생님의 기록을 모두 보므로, 기록마다 그때 진행한 선생님(story_completion.user_id)을 붙인다. 다른 반으로 옮긴
 * 학생은 지난 반의 기록도 보이므로(076) 기록마다 그때 반(classId, 그때 반 이름 className)도 붙인다.
 */
public record ClassStudentReportResponse(
        UUID id, String storyId, Instant completedAt, Integer durationSeconds, String sessionKind, UUID lessonId,
        UUID tutorId, String tutorDisplayName, UUID classId, String className) {

    public static ClassStudentReportResponse of(StoryCompletion completion) {
        return new ClassStudentReportResponse(
                completion.getId(), completion.getStoryId(), completion.getCompletedAt(),
                completion.getDurationSeconds(), completion.sessionKind(),
                completion.getLesson() == null ? null : completion.getLesson().getId(),
                completion.getUser().getId(), completion.getUser().getDisplayName(),
                completion.getClassGroup() == null ? null : completion.getClassGroup().getId(),
                completion.classNameSnapshot());
    }
}
