package com.qstory.backend.org.dto;

import com.qstory.backend.org.entity.TutorStudentClassHistory;
import java.time.Instant;
import java.util.UUID;

/**
 * 학생의 반 이력 한 구간(076). endedAt이 null이면 지금 반. reason은 시작된 이유(JOINED|MOVED|KEPT), endReason은 끝난
 * 이유(MOVED|KEPT|GRADUATED, 지금 반이면 null). className은 지금 반 이름이다(이력에는 이름 스냅샷이 없다).
 */
public record ClassHistoryEntryResponse(
        UUID classId, String className, boolean classArchived, Instant startedAt, Instant endedAt, String reason,
        String endReason) {

    public static ClassHistoryEntryResponse of(TutorStudentClassHistory entry) {
        return new ClassHistoryEntryResponse(
                entry.getClassGroup().getId(), entry.getClassGroup().getName(), entry.getClassGroup().isArchived(),
                entry.getStartedAt(), entry.getEndedAt(), entry.getReason(), entry.getEndReason());
    }
}
