package com.qstory.backend.org.report.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Aggregated at institution level only; no child's individual question text is exposed here. */
public record OrganizationReportResponse(
        Instant generatedAt,
        long completionCount,
        long questionCount,
        List<ClassSummary> classes,
        List<StorySummary> topStories) {

    public record ClassSummary(
            UUID classId,
            String className,
            long parentCount,
            long completionCount,
            long questionCount,
            Instant lastActivityAt) {}

    public record StorySummary(String storyId, long completionCount) {}
}
