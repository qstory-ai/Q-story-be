package com.qstory.backend.storyadmin.dto;

import com.qstory.backend.story.entity.StoryRevision;
import java.time.Instant;
import java.util.Map;

/** 스토리 편집 이력의 항목 하나. */
public record RevisionView(
        int revision,
        String targetType,
        String targetId,
        String operation,
        Map<String, Object> before,
        Map<String, Object> after,
        String authorId,
        String summary,
        Instant createdAt) {

    public static RevisionView of(StoryRevision row) {
        return new RevisionView(
                row.getRevision(),
                row.getTargetType().name(),
                row.getTargetId(),
                row.getOperation().name(),
                row.getBeforeState(),
                row.getAfterState(),
                row.getAuthorId() == null ? null : row.getAuthorId().toString(),
                row.getSummary(),
                row.getCreatedAt());
    }
}
