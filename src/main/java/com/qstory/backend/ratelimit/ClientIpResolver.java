package com.qstory.backend.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 요청을 보낸 클라이언트 주소. 웹은 모두 Vercel 프록시(fe/api/_qstory-proxy-core.mjs)를 거쳐 오므로 백엔드가 보는 주소는
 * Vercel 쪽 주소 몇 개뿐이다 - 프록시가 원래 주소를 x-qstory-client-ip로 넘기고, 나눠 가진 비밀로 서명한다:
 *
 * <pre>
 *   x-qstory-client-ip: 203.0.113.7
 *   x-qstory-proxy-ts:  1760000000            (유닉스 초)
 *   x-qstory-proxy-sig: hex(HMAC-SHA256(secret, ip + "\n" + ts))
 * </pre>
 *
 * 서명이 맞고 시각이 ±60초 안이면 그 주소를 쓴다. 아니면(네이티브 앱·WebSocket처럼 프록시를 안 거치거나, 서명이 틀리거나,
 * 비밀이 설정되지 않음) 설정한 주소 헤더(운영: Railway 에지가 덮어쓰는 X-Real-IP), 그것도 없으면 request.getRemoteAddr().
 * 아무나 x-qstory-client-ip를 붙여 보낼 수 있으므로 서명 없이 그 헤더를 믿는 일은 없다.
 */
public final class ClientIpResolver {

    public static final String CLIENT_IP_HEADER = "x-qstory-client-ip";
    public static final String TIMESTAMP_HEADER = "x-qstory-proxy-ts";
    public static final String SIGNATURE_HEADER = "x-qstory-proxy-sig";
    static final long MAX_SKEW_SECONDS = 60;

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);
    /** IPv4·IPv6 문자만 - 헤더 값이 로그·키로 그대로 쓰이지 않게 모양을 먼저 본다. */
    private static final Pattern IP_LIKE = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final long INVALID_LOG_INTERVAL_MS = 60_000;

    private final String secret;
    private final String fallbackHeader;
    private final Clock clock;
    private final AtomicLong lastInvalidLogAt = new AtomicLong();

    public ClientIpResolver(String secret, String fallbackHeader, Clock clock) {
        this.secret = secret == null ? "" : secret;
        this.fallbackHeader = fallbackHeader == null ? "" : fallbackHeader;
        this.clock = clock;
    }

    public String resolve(HttpServletRequest request) {
        String signedIp = request.getHeader(CLIENT_IP_HEADER);
        if (signedIp != null && !secret.isEmpty()) {
            if (verify(secret, signedIp, request.getHeader(TIMESTAMP_HEADER), request.getHeader(SIGNATURE_HEADER),
                    clock.millis() / 1000)) {
                return signedIp.trim();
            }
            logInvalid();
        }
        if (!fallbackHeader.isEmpty()) {
            String value = request.getHeader(fallbackHeader);
            if (value != null) {
                String first = value.split(",", 2)[0].trim();
                if (IP_LIKE.matcher(first).matches()) return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    /** 프록시 서명 확인 - 주소 모양, 시각(±60초), HMAC을 모두 본다. */
    static boolean verify(String secret, String ip, String timestamp, String signature, long nowSeconds) {
        if (ip == null || timestamp == null || signature == null) return false;
        String trimmedIp = ip.trim();
        if (!IP_LIKE.matcher(trimmedIp).matches()) return false;
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowSeconds - ts) > MAX_SKEW_SECONDS) return false;
        byte[] expected = sign(secret, trimmedIp, ts).getBytes(StandardCharsets.US_ASCII);
        byte[] provided = signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }

    /** hex(HMAC-SHA256(secret, ip + "\n" + ts)) - fe/api/_qstory-proxy-core.mjs의 signClientIp와 같아야 한다. */
    static String sign(String secret, String ip, long timestampSeconds) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((ip + "\n" + timestampSeconds).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 서명이 틀린 요청 - 1분에 한 줄만. 계속 찍히면 Vercel과 Railway의 QSTORY_PROXY_SHARED_SECRET이 서로 다르다는 뜻이고,
     * 그동안 웹 요청이 Vercel 주소 몇 개로 묶여 주소별 상한에 쉽게 걸린다.
     */
    private void logInvalid() {
        long now = clock.millis();
        long last = lastInvalidLogAt.get();
        if (now - last >= INVALID_LOG_INTERVAL_MS && lastInvalidLogAt.compareAndSet(last, now)) {
            log.warn("rate-limit.proxy-signature-invalid - x-qstory-client-ip 서명이 맞지 않아 연결 주소로 셉니다");
        }
    }
}
