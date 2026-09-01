package com.qstory.backend.tutor.lesson.dto;

import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 목록/상세 공통 응답. 학생은 요약(id, name, ageBand, status)만 실어 - 목록 카드에도 그대로
 * 쓰고 상세 헤더에도 쓸 수 있게. story_id 리스트도 그대로 실어 클라이언트가 카탈로그에서
 * 이름/표지를 join한다.
 */
public record LessonResponse(
        UUID id,
        String name,
        String goal,
        Instant scheduledAt,
        String status,
        Instant startedAt,
        Instant completedAt,
        List<StudentSummary> students,
        List<String> storyIds,
        Instant createdAt,
        Instant updatedAt) {

    public record StudentSummary(UUID id, String name, String ageBand, String status) {
        public static StudentSummary of(TutorStudent student) {
            return new StudentSummary(student.getId(), student.getName(), student.getAgeBand(), student.getStatus().name());
        }
    }

    public static LessonResponse of(Lesson lesson) {
        var students = lesson.getStudents().stream().map(StudentSummary::of).toList();
        // storyIds는 Set이라 렌더 순서를 안정화하려면 정렬 필요 - 문자열 사전순으로.
        var stories = lesson.getStoryIds().stream().sorted().toList();
        return new LessonResponse(
                lesson.getId(),
                lesson.getName(),
                lesson.getGoal(),
                lesson.getScheduledAt(),
                lesson.getStatus().name(),
                lesson.getStartedAt(),
                lesson.getCompletedAt(),
                students,
                stories,
                lesson.getCreatedAt(),
                lesson.getUpdatedAt());
    }
}
