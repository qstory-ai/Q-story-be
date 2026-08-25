package com.qstory.backend.storyreport.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record StoryCompletionDetail(
        UUID id, String storyId, Instant completedAt, Integer durationSeconds, List<Map<String, Object>> outcomes) {

    public static StoryCompletionDetail of(StoryCompletion completion) {
        return new StoryCompletionDetail(
                completion.getId(), completion.getStoryId(), completion.getCompletedAt(),
                completion.getDurationSeconds(), completion.getOutcomes());
    }
}
