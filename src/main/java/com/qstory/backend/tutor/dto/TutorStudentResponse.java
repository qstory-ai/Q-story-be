package com.qstory.backend.tutor.dto;

import com.qstory.backend.tutor.entity.TutorStudent;
import java.time.Instant;
import java.util.UUID;

public record TutorStudentResponse(
        UUID id, String name, String ageBand, String classType, String prepNote, String status,
        UUID linkedParentUserId, UUID childId, Instant createdAt,
        String lessonType, UUID classGroupId, String classGroupName, Integer birthYear) {

    public static TutorStudentResponse of(TutorStudent student) {
        return new TutorStudentResponse(
                student.getId(), student.getName(), student.currentAgeBand(), student.getClassType(),
                student.getPrepNote(), student.getStatus().name(),
                student.getLinkedParentUser() == null ? null : student.getLinkedParentUser().getId(),
                student.getChild() == null ? null : student.getChild().getId(),
                student.getCreatedAt(),
                student.getLessonType() == null ? "INDIVIDUAL" : student.getLessonType().name(),
                student.getClassGroup() == null ? null : student.getClassGroup().getId(),
                student.getClassGroup() == null ? null : student.getClassGroup().getName(),
                student.getBirthYear());
    }
}
