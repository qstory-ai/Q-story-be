package com.qstory.backend.storyreport.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 목록 화면용 - outcomes 페이로드를 포함하지 않으므로, 항목이 많아져도 이력 조회가 가볍게 유지된다.
 * childId는 부모(PARENT) 계정에서 어느 아이 프로필로 진행한 세션인지 - 클라이언트가 아이별
 * 필터를 걸 때 참조한다. 선생님 세션이나 legacy 기록에서는 null이다.
 *
 * <p>companionChatSummary는 상시 대화 태그 집계 스냅샷(있으면). record() 응답으로 넘겨줘,
 * 세션 직후 실시간 리포트 화면이 별도 왕복 없이 바로 렌더할 수 있게 한다.
 */
public record StoryCompletionSummary(
        UUID id,
        String storyId,
        Instant completedAt,
        Integer durationSeconds,
        UUID childId,
        Map<String, Object> companionChatSummary,
        /** 선생님 세션이면 어느 학생의 기록인지(id만 - 이름은 수업의 students로 조인). 가정 세션은 null. */
        UUID tutorStudentId,
        /** 수업 상세에서 시작한 세션이면 그 수업 id. */
        UUID lessonId) {

    public static StoryCompletionSummary of(StoryCompletion completion) {
        return new StoryCompletionSummary(
                completion.getId(),
                completion.getStoryId(),
                completion.getCompletedAt(),
                completion.getDurationSeconds(),
                completion.getChild() == null ? null : completion.getChild().getId(),
                completion.getCompanionChatSummary(),
                completion.getTutorStudent() == null ? null : completion.getTutorStudent().getId(),
                completion.getLesson() == null ? null : completion.getLesson().getId());
    }
}
