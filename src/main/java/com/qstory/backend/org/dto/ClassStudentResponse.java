package com.qstory.backend.org.dto;

import com.qstory.backend.tutor.entity.TutorStudent;
import java.time.Instant;
import java.util.UUID;

/** 반 상세의 학생 명단 한 줄 - 학부모가 아직 연결되지 않은 학생은 parentDisplayName이 null이다. */
public record ClassStudentResponse(
        UUID id, String name, String ageBand, String status, String parentDisplayName, String parentEmail,
        Instant createdAt) {

    public static ClassStudentResponse of(TutorStudent student) {
        return new ClassStudentResponse(
                student.getId(), student.getName(), student.currentAgeBand(), student.getStatus().name(),
                student.getLinkedParentUser() == null ? null : student.getLinkedParentUser().getDisplayName(),
                student.getLinkedParentUser() == null ? null : student.getLinkedParentUser().getEmail(),
                student.getCreatedAt());
    }
}
