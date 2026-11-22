package com.qstory.backend.common.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 환경변수로 받은 Google 서비스 계정 키 - JSON 원문이거나 그 base64(일반·URL-safe, 줄바꿈 허용). GA·FCM·GCP 청구가 같이 쓴다. */
public final class ServiceAccountJson {

    private ServiceAccountJson() {}

    /** JSON 원문이면 그대로, 아니면 base64로 풀어 본다. 둘 다 아니면 null. */
    public static String decode(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        for (Base64.Decoder decoder : new Base64.Decoder[] {Base64.getMimeDecoder(), Base64.getUrlDecoder()}) {
            try {
                String decoded = new String(decoder.decode(trimmed), StandardCharsets.UTF_8).trim();
                if (decoded.startsWith("{")) {
                    return decoded;
                }
            } catch (IllegalArgumentException notBase64) {
                // 다음 디코더로.
            }
        }
        return null;
    }
}
