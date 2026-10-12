package com.qstory.backend.identity.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** auth 라우트를 위한 요청 형태(request-shape) 검증. */
@Component
public class AuthValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** 아이디 - 이메일과 별개인 자유 형식 식별자. 영문/숫자/일부 특수문자, 4~30자. */
    private static final Pattern LOGIN_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{4,30}$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    /**
     * BCrypt는 앞 72바이트만 본다. Spring Security 6.5부터 BCryptPasswordEncoder.encode가 그보다 긴 비밀번호에
     * IllegalArgumentException을 던지므로(CVE-2025-22228) 여기서 먼저 400으로 거절한다 - 한글은 한 글자에 3바이트라
     * 24자 남짓에서 걸린다. 업그레이드 전 72바이트를 넘겨 가입한 계정의 로그인(matches)은 그대로 된다.
     */
    static final int MAX_PASSWORD_BYTES = 72;

    public void validateSignup(SignupOrganizationOwnerRequest request) {
        if (isBlank(request.loginId()) || !LOGIN_ID_PATTERN.matcher(request.loginId().trim()).matches()) {
            throw ApiException.contractError(
                    ErrorCode.VALIDATION_FAILED, "아이디는 4~30자의 영문, 숫자, 특수문자(.,_,-)만 사용할 수 있어요.");
        }
        if (isBlank(request.email()) || !EMAIL_PATTERN.matcher(request.email().trim()).matches()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "올바른 이메일 주소를 입력해 주세요.");
        }
        validatePassword(request.password());
        validateDisplayName(request.displayName());
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
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.contractError(
                    ErrorCode.VALIDATION_FAILED, "비밀번호가 너무 길어요. 영문 72자(한글 24자) 이하로 입력해 주세요.");
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
