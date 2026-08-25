package com.qstory.backend.storyreport.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.UUID;

/** 목록 화면용 - outcomes 페이로드를 포함하지 않으므로, 항목이 많아져도 이력 조회가 가볍게 유지된다. */
public record StoryCompletionSummary(UUID id, String storyId, Instant completedAt, Integer durationSeconds) {

    public static StoryCompletionSummary of(StoryCompletion completion) {
        return new StoryCompletionSummary(
                completion.getId(), completion.getStoryId(), completion.getCompletedAt(), completion.getDurationSeconds());
    }
}
