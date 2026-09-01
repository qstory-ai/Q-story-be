package com.qstory.backend.org.tutor.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 발급 응답. token은 한 번만 노출되는 원본 시크릿(저장은 sha-256만) - 클라이언트가 즉시 UI에
 * 노출해서 관리자가 복사하도록 해야 한다. shortCode는 손으로 옮길 수 있는 형태로, 링크와 같은
 * 초대를 가리키지만 시크릿은 아니라 안전 채널 요건이 링크보다 덜 엄격하다.
 */
public record OrganizationTutorInviteResponse(
        UUID id, String token, String shortCode, Instant expiresAt) {}
