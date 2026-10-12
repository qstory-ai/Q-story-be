package com.qstory.backend.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 요청 상한(보안 점검 M1/M2) 설정. 문제가 생기면 QSTORY_RATE_LIMIT_ENABLED=false 하나로 모든 상한을 끈다.
 *
 * @param enabled 끄면 상한을 하나도 보지 않는다.
 * @param proxySharedSecret Vercel 프록시와 나눠 가진 비밀(QSTORY_PROXY_SHARED_SECRET). 프록시가 이 비밀로 서명한
 *     x-qstory-client-ip만 믿는다. 비어 있으면 그 헤더를 보지 않는다.
 * @param clientIpHeader 서명이 없을 때(네이티브 앱·WebSocket처럼 프록시를 거치지 않는 호출) 볼 주소 헤더. 운영(Railway)은
 *     에지 프록시가 매 요청마다 덮어쓰는 X-Real-IP. 비어 있으면 request.getRemoteAddr().
 * @param ttsAnonymousDaily 로그인하지 않은 실시간 TTS(기본 Gemini 키)의 하루 상한 - Gemini 하루 한도와 같은 시각
 *     (미국 태평양 자정)에 다시 센다. 0 이하면 보지 않는다.
 * @param limits 상한별 덮어쓰기(키 = RateLimitRule.id()). 빠진 값은 RateLimitRule의 기본값.
 * @param maxTrackedKeys 기억하는 버킷 수 상한 - 넘으면 다 찬(쉬고 있는) 버킷부터 지운다.
 */
@ConfigurationProperties(prefix = "qstory.rate-limit")
public record RateLimitProperties(
        Boolean enabled,
        String proxySharedSecret,
        String clientIpHeader,
        Integer ttsAnonymousDaily,
        Map<String, Limit> limits,
        Integer maxTrackedKeys) {

    public record Limit(Integer requests, Duration window) {}

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        proxySharedSecret = proxySharedSecret == null ? "" : proxySharedSecret.trim();
        clientIpHeader = clientIpHeader == null ? "" : clientIpHeader.trim();
        ttsAnonymousDaily = ttsAnonymousDaily == null ? 80 : ttsAnonymousDaily;
        limits = limits == null ? Map.of() : Map.copyOf(limits);
        maxTrackedKeys = maxTrackedKeys == null || maxTrackedKeys <= 0 ? 50_000 : maxTrackedKeys;
    }

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(true, "", "", 80, Map.of(), 50_000);
    }

    public int requestsFor(RateLimitRule rule) {
        Limit limit = limits.get(rule.id());
        return limit != null && limit.requests() != null && limit.requests() > 0 ? limit.requests() : rule.defaultRequests();
    }

    public Duration windowFor(RateLimitRule rule) {
        Limit limit = limits.get(rule.id());
        return limit != null && limit.window() != null && !limit.window().isNegative() && !limit.window().isZero()
                ? limit.window()
                : rule.defaultWindow();
    }
}
