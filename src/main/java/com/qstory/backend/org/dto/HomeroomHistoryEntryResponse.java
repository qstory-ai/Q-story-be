package com.qstory.backend.org.dto;

import com.qstory.backend.org.entity.ClassHomeroomHistory;
import java.time.Instant;
import java.util.UUID;

/** 반 담임 이력 한 구간. endedAt이 null이면 지금 담임. */
public record HomeroomHistoryEntryResponse(UUID tutorId, String tutorDisplayName, Instant startedAt, Instant endedAt) {

    public static HomeroomHistoryEntryResponse of(ClassHomeroomHistory entry) {
        return new HomeroomHistoryEntryResponse(
                entry.getTutor().getId(), entry.getTutor().getDisplayName(), entry.getStartedAt(), entry.getEndedAt());
    }
}
