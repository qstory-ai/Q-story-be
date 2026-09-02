package com.qstory.backend.storyreport.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.UUID;

/**
 * 목록 화면용 - outcomes 페이로드를 포함하지 않으므로, 항목이 많아져도 이력 조회가 가볍게 유지된다.
 * childId는 부모(PARENT) 계정에서 어느 아이 프로필로 진행한 세션인지 - 클라이언트가 아이별
 * 필터를 걸 때 참조한다. 방문 선생님 세션이나 legacy 기록에서는 null이다.
 */
public record StoryCompletionSummary(
        UUID id, String storyId, Instant completedAt, Integer durationSeconds, UUID childId) {

    public static StoryCompletionSummary of(StoryCompletion completion) {
        return new StoryCompletionSummary(
                completion.getId(),
                completion.getStoryId(),
                completion.getCompletedAt(),
                completion.getDurationSeconds(),
                completion.getChild() == null ? null : completion.getChild().getId());
    }
}
