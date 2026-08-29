package com.qstory.backend.common.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.time.Instant;

/**
 * ClassService.resolveByInvite()/TutorStudentService.previewInvite()/acceptInvite()/
 * AuthService.confirmPasswordReset()이 각자 손으로 다시 작성했던 "1회용 토큰이 이미 쓰였거나
 * 만료됐으면 던진다" 검사를 하나로 모았다. 엔티티(ClassInvite/TutorInvite/PasswordResetToken)는
 * 서로 무관한 클래스라 공통 인터페이스를 새로 만들지 않고, usedAt/expiresAt을 그대로 받는다.
 */
public final class TokenValidation {

    private TokenValidation() {}

    public static void requireUsable(Instant usedAt, Instant expiresAt, ErrorCode code, String safeDetail, int statusCode) {
        if (usedAt != null || expiresAt.isBefore(Instant.now())) {
            throw ApiException.contractError(code, safeDetail, statusCode);
        }
    }
}
