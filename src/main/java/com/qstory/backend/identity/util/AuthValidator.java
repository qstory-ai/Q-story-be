package com.qstory.backend.identity.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** auth 라우트를 위한 요청 형태(request-shape) 검증. AuthService에서 분리되어 나왔다(VoiceResearchValidator의 분리 방식과 동일). */
@Component
public class AuthValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    public void validateSignup(SignupOrganizationOwnerRequest request) {
        if (isBlank(request.email()) || !EMAIL_PATTERN.matcher(request.email().trim()).matches()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "올바른 이메일 주소를 입력해 주세요.");
        }
        validatePassword(request.password());
        if (isBlank(request.displayName()) || request.displayName().length() > 60) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "이름을 입력해 주세요.");
        }
    }

    public void validateLogin(LoginRequest request) {
        if (isBlank(request.loginId()) || isBlank(request.password())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "아이디와 비밀번호를 입력해 주세요.");
        }
    }

    public void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > 100) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "비밀번호는 8자 이상 입력해 주세요.");
        }
    }

    public void validateDisplayName(String displayName) {
        if (isBlank(displayName) || displayName.length() > 60) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "이름을 입력해 주세요.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
