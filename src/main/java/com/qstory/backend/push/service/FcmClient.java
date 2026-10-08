package com.qstory.backend.push.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.config.FcmProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FCM HTTP v1(`projects/{projectId}/messages:send`)로 토큰 하나에 메시지 하나를 보낸다. 서비스 계정 키가 없거나
 * 읽을 수 없으면 enabled()가 false이고 부팅 때 fcm.disabled 한 줄만 남긴다 - 인앱 알림은 영향 없다.
 *
 * <p>로그에는 토큰을 남기지 않는다(tokenHash=SHA-256 앞 8자리만). 실패 태그 fcm.send-failed는
 * ops/grafana-alerting/alert_rules.tf의 fcm-push-failure 규칙이 센다 - disabled=true(앱 삭제 등으로 토큰이
 * 죽은, 늘 있는 경우)는 규칙에서 뺀다.
 */
@Component
public class FcmClient {

    private static final Logger log = LoggerFactory.getLogger(FcmClient.class);
    static final String BASE_URL = "https://fcm.googleapis.com/v1/projects/";
    /** fe 앱(Capacitor)이 만드는 Android 알림 채널 id - 앱과 같이 바꿔야 한다. */
    static final String ANDROID_CHANNEL_ID = "qstory_default";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String projectId;
    private final FcmAccessTokenProvider tokens;

    @Autowired
    public FcmClient(HttpClient httpClient, ObjectMapper objectMapper, FcmProperties properties) {
        this(httpClient, objectMapper, Setup.from(properties, objectMapper));
    }

    /** 테스트용 - projectId나 tokens가 null이면 꺼진 클라이언트. */
    FcmClient(HttpClient httpClient, ObjectMapper objectMapper, String projectId, FcmAccessTokenProvider tokens) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.projectId = projectId;
        this.tokens = tokens;
    }

    private FcmClient(HttpClient httpClient, ObjectMapper objectMapper, Setup setup) {
        this(httpClient, objectMapper, setup.projectId(), setup.tokens());
    }

    public boolean enabled() {
        return projectId != null && tokens != null;
    }

    public FcmSendResult send(String token, PushMessage message) {
        if (!enabled()) {
            return new FcmSendResult(FcmSendResult.Outcome.FAILED, 0, "disabled");
        }
        String accessToken;
        try {
            accessToken = tokens.accessToken();
        } catch (IOException | RuntimeException error) {
            return failed(token, 0, "auth", false, message);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + projectId + "/messages:send"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body(token, message))))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status / 100 == 2) {
                return FcmSendResult.sent(status);
            }
            return classifyError(token, status, response.body(), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(token, 0, "interrupted", false, message);
        } catch (IOException | RuntimeException error) {
            return failed(token, 0, "network", false, message);
        }
    }

    /** FCM v1 메시지. data 값은 모두 문자열이어야 하고, 비어 있는 값은 넣지 않는다. */
    ObjectNode body(String token, PushMessage message) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode msg = root.putObject("message");
        msg.put("token", token);
        ObjectNode notification = msg.putObject("notification");
        notification.put("title", message.title());
        if (notBlank(message.body())) {
            notification.put("body", message.body());
        }
        ObjectNode data = msg.putObject("data");
        if (notBlank(message.href())) {
            data.put("href", message.href());
        }
        if (notBlank(message.kind())) {
            data.put("kind", message.kind());
        }
        if (message.notificationId() != null) {
            data.put("notificationId", message.notificationId().toString());
        }
        ObjectNode android = msg.putObject("android");
        android.put("priority", "high");
        android.putObject("notification").put("channel_id", ANDROID_CHANNEL_ID);
        return root;
    }

    /**
     * 오류 본문: {"error":{"code":404,"status":"NOT_FOUND","details":[{"@type":"...FcmError","errorCode":"UNREGISTERED"},
     * {"@type":"...BadRequest","fieldViolations":[{"field":"message.token",...}]}]}}. 토큰이 죽은 경우만 TOKEN_INVALID로
     * 본다: UNREGISTERED, 404, 다른 Firebase 프로젝트의 토큰(SENDER_ID_MISMATCH), 토큰 형식이 틀린 INVALID_ARGUMENT.
     * 페이로드가 틀린 INVALID_ARGUMENT는 토큰 잘못이 아니므로 토큰을 그대로 둔다.
     */
    private FcmSendResult classifyError(String token, int status, byte[] responseBody, PushMessage message) {
        String errorStatus = null;
        String errorCode = null;
        boolean tokenField = false;
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("error");
            errorStatus = error.path("status").asText(null);
            for (JsonNode detail : error.path("details")) {
                String type = detail.path("@type").asText("");
                if (type.endsWith("FcmError") && detail.hasNonNull("errorCode")) {
                    errorCode = detail.path("errorCode").asText();
                }
                for (JsonNode violation : detail.path("fieldViolations")) {
                    if (violation.path("field").asText("").endsWith("token")) {
                        tokenField = true;
                    }
                }
            }
            if (error.path("message").asText("").toLowerCase(Locale.ROOT).contains("registration token")) {
                tokenField = true;
            }
        } catch (IOException | RuntimeException unreadable) {
            // 본문을 못 읽어도 HTTP 상태로 판단한다.
        }
        String reason = errorCode != null ? errorCode : errorStatus != null ? errorStatus : "http-" + status;
        boolean invalidArgument = "INVALID_ARGUMENT".equals(errorCode)
                || (errorCode == null && "INVALID_ARGUMENT".equals(errorStatus));
        boolean tokenDead = "UNREGISTERED".equals(errorCode)
                || "SENDER_ID_MISMATCH".equals(errorCode)
                || status == 404
                || (invalidArgument && tokenField);
        return failed(token, status, reason, tokenDead, message);
    }

    private FcmSendResult failed(String token, int status, String reason, boolean tokenDead, PushMessage message) {
        log.warn("fcm.send-failed status={} reason={} disabled={} kind={} tokenHash={}",
                status, reason, tokenDead, message.kind(), tokenHash(token));
        return new FcmSendResult(
                tokenDead ? FcmSendResult.Outcome.TOKEN_INVALID : FcmSendResult.Outcome.FAILED, status, reason);
    }

    /** 로그용 - 토큰 원문 대신 SHA-256 앞 8자리. */
    static String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException impossible) {
            return "-";
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 설정에서 projectId와 토큰 발급기를 만든다. 실패 사유는 로그로만 남기고 꺼진 상태로 뜬다(부팅은 막지 않는다). */
    record Setup(String projectId, FcmAccessTokenProvider tokens) {

        private static final Setup DISABLED = new Setup(null, null);

        static Setup from(FcmProperties properties, ObjectMapper objectMapper) {
            String raw = properties == null ? null : properties.serviceAccountJson();
            if (!notBlank(raw)) {
                log.info("fcm.disabled reason=not-configured");
                return DISABLED;
            }
            String json = decodeServiceAccount(raw);
            if (json == null) {
                log.warn("fcm.disabled reason=invalid-service-account detail=not-json-or-base64");
                return DISABLED;
            }
            String projectId = properties.projectId();
            if (!notBlank(projectId)) {
                try {
                    projectId = objectMapper.readTree(json).path("project_id").asText(null);
                } catch (IOException unreadable) {
                    log.warn("fcm.disabled reason=invalid-service-account detail=unreadable-json");
                    return DISABLED;
                }
            }
            if (!notBlank(projectId)) {
                log.warn("fcm.disabled reason=no-project-id");
                return DISABLED;
            }
            try {
                FcmAccessTokenProvider tokens = new GoogleFcmAccessTokenProvider(json);
                log.info("fcm.enabled projectId={}", projectId.trim());
                return new Setup(projectId.trim(), tokens);
            } catch (IOException | RuntimeException invalid) {
                log.warn("fcm.disabled reason=invalid-service-account detail={}", invalid.getClass().getSimpleName());
                return DISABLED;
            }
        }

        /** JSON 원문이면 그대로, 아니면 base64(일반·URL-safe, 줄바꿈 허용)로 풀어 본다. 둘 다 아니면 null. */
        static String decodeServiceAccount(String raw) {
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
}
