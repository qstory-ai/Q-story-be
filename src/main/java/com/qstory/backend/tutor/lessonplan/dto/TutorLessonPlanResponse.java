package com.qstory.backend.tutor.lessonplan.dto;

import com.qstory.backend.tutor.lessonplan.entity.TutorLessonPlan;
import java.time.Instant;
import java.util.UUID;

public record TutorLessonPlanResponse(
        UUID id, UUID tutorStudentId, String studentName, String storyId, Instant addedAt) {

    public static TutorLessonPlanResponse of(TutorLessonPlan plan) {
        return new TutorLessonPlanResponse(
                plan.getId(),
                plan.getTutorStudent().getId(),
                plan.getTutorStudent().getName(),
                plan.getStoryId(),
                plan.getAddedAt());
    }
}
