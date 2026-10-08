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
            long studentCount,
            long completionCount,
            long questionCount,
            Instant lastActivityAt,
            /** 지난 반(076) - 보관된 반도 지난 기록이 있으면 집계에 남는다. studentCount는 지금 학생(졸업 제외)만. */
            boolean archived) {}

    public record StorySummary(String storyId, long completionCount) {}
}
