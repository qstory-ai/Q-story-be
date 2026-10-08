package com.qstory.backend.org.dto;

import com.qstory.backend.org.entity.TutorStudentClassHistory;
import com.qstory.backend.tutor.entity.TutorStudent;
import java.time.Instant;
import java.util.UUID;

/**
 * 반 상세의 학생 명단 한 줄 - 학부모가 아직 연결되지 않은 학생은 parentDisplayName이 null이다.
 *
 * <p>지난 학생(076, includePast=true)은 endedAt(이 반을 떠난 시각)과 endReason(MOVED 다른 반으로 옮김, GRADUATED 졸업,
 * KEPT는 쓰지 않는다)이 채워지고, 졸업했으면 graduatedAt도 채워진다. 지금 학생은 셋 다 null이다.
 */
public record ClassStudentResponse(
        UUID id, String name, String ageBand, String status, String parentDisplayName, String parentEmail,
        Instant createdAt, Instant graduatedAt, Instant endedAt, String endReason) {

    public static ClassStudentResponse of(TutorStudent student) {
        return of(student, null, null);
    }

    public static ClassStudentResponse past(TutorStudentClassHistory ended) {
        return of(ended.getTutorStudent(), ended.getEndedAt(), ended.getEndReason());
    }

    private static ClassStudentResponse of(TutorStudent student, Instant endedAt, String endReason) {
        return new ClassStudentResponse(
                student.getId(), student.getName(), student.currentAgeBand(), student.getStatus().name(),
                student.getLinkedParentUser() == null ? null : student.getLinkedParentUser().getDisplayName(),
                student.getLinkedParentUser() == null ? null : student.getLinkedParentUser().getEmail(),
                student.getCreatedAt(), student.getGraduatedAt(), endedAt, endReason);
    }
}
