package com.qstory.backend.common.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.time.Instant;

/**
 * "1회용 토큰이 이미 쓰였거나 만료됐으면 던진다" 공용 검사. 토큰 엔티티(OrganizationTutorInvite/
 * PasswordResetToken)는 서로 무관한 클래스라 공통 인터페이스 없이
 * usedAt/expiresAt을 그대로 받는다.
 */
public final class TokenValidation {

    private TokenValidation() {}

    public static void requireUsable(Instant usedAt, Instant expiresAt, ErrorCode code, String safeDetail, int statusCode) {
        if (usedAt != null || expiresAt.isBefore(Instant.now())) {
            throw ApiException.contractError(code, safeDetail, statusCode);
        }
    }
}
