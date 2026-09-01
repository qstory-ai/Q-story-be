package com.qstory.backend.tutor.lessonplan.dto;

import java.util.UUID;

public record CreateTutorLessonPlanRequest(UUID tutorStudentId, String storyId) {}
