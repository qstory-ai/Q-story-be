package com.qstory.backend.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 공용 해싱 헬퍼. 이전에는 voiceresearch와 shadow 양쪽에 중복 구현돼 있었다. */
public final class DigestUtil {

    private DigestUtil() {}

    public static String sha256Hex(String value) {
        return hex("SHA-256", value);
    }

    public static String md5Hex(String value) {
        return hex("MD5", value);
    }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * X-Admin-Token 헤더 검증 - AuthController(STAFF 계정 발급)와 StoryImportController(콘텐츠
     * 임포트) 둘 다 이 비교를 썼는데 각자 따로 손으로 다시 작성돼 있었다. "제출된 토큰이 설정된
     * 토큰과 실제로 일치하는가"라는 보안 비교 로직을 하나로 모았다 - 두 사본이 따로 수정되다
     * 어긋나는 게 진짜 위험이다. (두 컨트롤러는 이제 검증 실패 시 둘 다 ApiException/ErrorCode.FORBIDDEN을
     * 던진다 - 예전에는 StoryImportController가 별도의 EdgeException 체계를 썼지만 통합했다.)
     */
    public static boolean matchesAdminToken(String provided, String configured) {
        return provided != null && configured != null && constantTimeEquals(configured, provided);
    }

    private static String hex(String algorithm, String value) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
