package com.qstory.backend.storyreport.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * sessionKind·className·organizationName·tutorDisplayName·participantCount는 리포트 머리말용이다 - 반 수업
 * 기록(CLASS)은 "우리 반이 함께 나눈 이야기"로, 우리 아이 발화처럼 보이지 않게 그린다.
 */
public record StoryCompletionDetail(
        UUID id,
        String storyId,
        Instant completedAt,
        Integer durationSeconds,
        UUID childId,
        List<Map<String, Object>> outcomes,
        Map<String, Object> companionChatSummary,
        UUID tutorStudentId,
        UUID lessonId,
        String sessionKind,
        String className,
        String organizationName,
        String tutorDisplayName,
        int participantCount) {

    public static StoryCompletionDetail of(StoryCompletion completion) {
        String kind = completion.sessionKind();
        return new StoryCompletionDetail(
                completion.getId(),
                completion.getStoryId(),
                completion.getCompletedAt(),
                completion.getDurationSeconds(),
                completion.getChild() == null ? null : completion.getChild().getId(),
                completion.getOutcomes(),
                completion.getCompanionChatSummary(),
                completion.getTutorStudent() == null ? null : completion.getTutorStudent().getId(),
                completion.getLesson() == null ? null : completion.getLesson().getId(),
                kind,
                completion.getClassGroup() == null ? null : completion.getClassGroup().getName(),
                completion.getOrganization() == null ? null : completion.getOrganization().getName(),
                "HOME".equals(kind) ? null : completion.getUser().getDisplayName(),
                completion.getParticipants().size());
    }
}
