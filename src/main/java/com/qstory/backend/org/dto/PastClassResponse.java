package com.qstory.backend.org.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 선생님이 예전에 맡았던 반(076, GET /v1/tutor-classes/past) - 담임이 바뀌었거나 지난 반으로 보관된 반. ledFrom~ledUntil은
 * 이 선생님이 담임이었던 기간(여러 번 맡았으면 처음 시작 ~ 마지막 끝), 보관된 반을 아직 맡고 있으면 ledUntil이 null.
 * 반 코드는 보내지 않는다.
 */
public record PastClassResponse(
        UUID id, UUID organizationId, String organizationName, String name, Instant archivedAt, Instant ledFrom,
        Instant ledUntil) {}
