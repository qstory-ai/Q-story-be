package com.qstory.backend.common.util;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * 1회용 비밀 토큰(24바이트 base64url) 생성기 - 비밀번호 재설정(AuthService)과 선생님/기관 초대
 * (TutorStudentService/OrganizationTutorService)가 공유한다.
 */
@Component
public class SecureTokenGenerator {

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
