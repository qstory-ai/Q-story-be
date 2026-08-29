package com.qstory.backend.common.util;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * AuthService(비밀번호 재설정)/ClassService(반 초대)/TutorStudentService(초대)가 각자 자기만의
 * SecureRandom 인스턴스를 들고 있으면서 완전히 동일한 24바이트 base64url 토큰 생성 로직을 손으로
 * 다시 작성해 갖고 있던 것을 하나로 모았다.
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
