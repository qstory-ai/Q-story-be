package com.qstory.backend.tutor.dto;

import com.qstory.backend.tutor.entity.TutorStudent;
import java.time.Instant;
import java.util.UUID;

public record TutorStudentResponse(
        UUID id, String name, String ageBand, String classType, String prepNote, String status,
        UUID linkedParentUserId, Instant createdAt) {

    public static TutorStudentResponse of(TutorStudent student) {
        return new TutorStudentResponse(
                student.getId(), student.getName(), student.getAgeBand(), student.getClassType(),
                student.getPrepNote(), student.getStatus().name(),
                student.getLinkedParentUser() == null ? null : student.getLinkedParentUser().getId(),
                student.getCreatedAt());
    }
}
