package com.qstory.backend.provider.rtzr.util;
import com.qstory.backend.provider.rtzr.RtzrTranscriptionResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** RTZR(VITO) 배치 STT 클라이언트: 토큰을 캐싱하는 인증, 멀티파트 제출, 결과 폴링. */
@Component
public class RtzrSttClient {

    private static final Logger log = LoggerFactory.getLogger(RtzrSttClient.class);
    /** OpenRouterClient.FAILURE_BODY_LOG_LIMIT과 같은 이유로 잘라 남긴다 - 응답 본문이 길면 로그 부담. */
    private static final int FAILURE_BODY_LOG_LIMIT = 500;
    /** 400이어도 오디오 문제가 아니라 업체 계정·결제 문제인 RTZR 오류 코드(H0001: 카드 등록 필요). */
    private static final java.util.Set<String> BILLING_ACCOUNT_CODES = java.util.Set.of("H0001");
    private static final String BASE_URL = "https://openapi.vito.ai";
    /**
     * 결과를 확인하기 전 기다리는 시간 - 처음에는 짧게, 그다음 1.5초. 아이의 짧은 말은 1~2초 안에 끝나는데
     * 1.5초 고정 간격이면 끝나고도 최대 1.5초를 더 기다렸다(PM 피드백 - 로딩이 길다).
     */
    static final List<Duration> POLL_DELAYS = List.of(
            Duration.ofMillis(500), Duration.ofMillis(500), Duration.ofMillis(700), Duration.ofMillis(1_000));
    private static final Duration POLL_INTERVAL = Duration.ofMillis(1_500);

    static Duration pollDelay(int attempt) {
        return attempt < POLL_DELAYS.size() ? POLL_DELAYS.get(attempt) : POLL_INTERVAL;
    }

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;

    private record CachedToken(String accessToken, long expiresAtMillis) {}

    private volatile CachedToken cachedToken;

    public RtzrSttClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientId = config.providers().rtzr().clientId();
        this.clientSecret = config.providers().rtzr().clientSecret();
    }

    public RtzrTranscriptionResult transcribe(
            byte[] audio, String extension, String mimeType, List<String> keywords, RequestDeadline deadline) {
        try {
            String accessToken = authenticate(deadline);
            String submissionId = submit(accessToken, audio, extension, mimeType, keywords, deadline);
            long started = System.nanoTime();
            RtzrTranscriptionResult result = poll(accessToken, submissionId, deadline);
            // 단계별 지연을 Grafana에서 나눠 보려고 남긴다(문장은 남기지 않는다).
            log.info("rtzr-stt.ok poll_ms={} audio_bytes={}", (System.nanoTime() - started) / 1_000_000, audio.length);
            return result;
        } catch (ProviderException | AbortException | ApiException known) {
            throw known;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AbortException("request-timeout");
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.RTZR_NETWORK_FAILED, "한국어 음성 인식 서버에 연결하지 못했어요.", true, error);
        }
    }

    private synchronized String authenticate(RequestDeadline deadline) throws Exception {
        long now = System.currentTimeMillis();
        CachedToken current = cachedToken;
        if (current != null && current.expiresAtMillis() > now + Duration.ofMinutes(30).toMillis()) {
            return current.accessToken();
        }

        String form = "client_id=" + urlEncode(clientId) + "&client_secret=" + urlEncode(clientSecret);
        HttpRequest request = deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/authenticate"))
                        .header("accept", "application/json")
                        .header("content-type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode payload = safeJson(response.body());
        if (response.statusCode() / 100 != 2 || payload == null || !payload.hasNonNull("access_token")) {
            logRtzrHttpFailure("authenticate", response.statusCode(), response.body());
            throwIfProviderUnavailable("authenticate", response.statusCode(), payload);
            throw new ProviderException(
                    ProviderErrorCode.RTZR_AUTH_FAILED, "한국어 음성 인식 인증에 실패했어요.", response.statusCode() >= 500);
        }
        long expiresAtMillis = payload.hasNonNull("expire_at")
                ? payload.get("expire_at").asLong() * 1000
                : now + Duration.ofMinutes(330).toMillis();
        CachedToken token = new CachedToken(payload.get("access_token").asText(), expiresAtMillis);
        cachedToken = token;
        return token.accessToken();
    }

    private String submit(
            String accessToken, byte[] audio, String extension, String mimeType, List<String> keywords,
            RequestDeadline deadline) throws Exception {
        Map<String, Object> config = Map.of(
                "model_name", "sommers",
                "language", "ko",
                "use_diarization", false,
                "use_itn", true,
                "use_disfluency_filter", true,
                "use_profanity_filter", true,
                "use_paragraph_splitter", false,
                "domain", "GENERAL",
                "keywords", keywords == null ? List.of() : keywords);
        String boundary = "qstory-" + java.util.UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, audio, extension, mimeType, objectMapper.writeValueAsString(config));

        HttpRequest request = deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/transcribe"))
                        .header("accept", "application/json")
                        .header("authorization", "Bearer " + accessToken)
                        .header("content-type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode payload = safeJson(response.body());
        if (response.statusCode() / 100 != 2 || payload == null || !payload.hasNonNull("id")) {
            logRtzrHttpFailure("submit", response.statusCode(), response.body());
            throwIfProviderUnavailable("submit", response.statusCode(), payload);
            throw new ProviderException(
                    ProviderErrorCode.RTZR_SUBMIT_FAILED, "녹음을 음성 인식기에 전달하지 못했어요.", response.statusCode() >= 429);
        }
        return payload.get("id").asText();
    }

    private RtzrTranscriptionResult poll(String accessToken, String submissionId, RequestDeadline deadline) throws Exception {
        for (int attempt = 0; ; attempt++) {
            deadline.requireTimeRemaining();
            Thread.sleep(pollDelay(attempt).toMillis());
            HttpRequest request = deadline.applyTo(HttpRequest.newBuilder(
                                URI.create(BASE_URL + "/v1/transcribe/" + java.net.URLEncoder.encode(submissionId, StandardCharsets.UTF_8)))
                            .header("accept", "application/json")
                            .header("authorization", "Bearer " + accessToken)
                            .GET())
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode result = safeJson(response.body());
            if (response.statusCode() / 100 != 2) {
                logRtzrHttpFailure("poll", response.statusCode(), response.body());
                throwIfProviderUnavailable("poll", response.statusCode(), result);
                throw new ProviderException(
                        ProviderErrorCode.RTZR_RESULT_FAILED, "음성 인식 결과를 가져오지 못했어요.", response.statusCode() >= 429);
            }
            String status = result != null && result.hasNonNull("status") ? result.get("status").asText() : null;
            if ("failed".equals(status)) {
                // Rtzr가 200으로 응답하고도 status="failed"를 낸 케이스 - HTTP 실패와 구분되는 별도 태그로
                // 남긴다. 원인이 서버 쪽이라 사용자 재시도로는 안 풀리는 경우가 많아 별도 알림 라인.
                logRtzrTranscriptionFailed(submissionId, response.body());
                throw new ProviderException(
                        ProviderErrorCode.RTZR_TRANSCRIPTION_FAILED, "이번 목소리를 문장으로 바꾸지 못했어요.");
            }
            if ("completed".equals(status)) {
                JsonNode utterances = result.path("results").path("utterances");
                List<String> parts = new ArrayList<>();
                String locale = "ko";
                boolean first = true;
                if (utterances.isArray()) {
                    for (JsonNode utterance : utterances) {
                        String msg = utterance.path("msg").asText("").trim();
                        if (!msg.isEmpty()) {
                            parts.add(msg);
                        }
                        if (first && utterance.hasNonNull("lang")) {
                            locale = utterance.get("lang").asText();
                        }
                        first = false;
                    }
                }
                return new RtzrTranscriptionResult(
                        String.join(" ", parts).trim(), locale, submissionId);
            }
        }
    }

    /**
     * 업체 쪽 결제·인증 문제로 재시도해도 풀리지 않는 경우만 503 STT_UNAVAILABLE로 구분한다.
     * 401/402/403은 항상, 400은 인증 단계(클라이언트 자격 증명 오류)이거나 본문 code가
     * {@link #BILLING_ACCOUNT_CODES}일 때만. 그 외 4xx(깨진 오디오 400, 404, 413, 415, 422 등)와
     * 429, 5xx는 기존 ProviderException 흐름을 그대로 탄다.
     */
    private void throwIfProviderUnavailable(String context, int statusCode, JsonNode payload) {
        String code = payload != null && payload.hasNonNull("code") ? payload.get("code").asText() : "unknown";
        boolean unavailable = statusCode == 401 || statusCode == 402 || statusCode == 403
                || (statusCode == 400 && ("authenticate".equals(context) || BILLING_ACCOUNT_CODES.contains(code)));
        if (!unavailable) {
            return;
        }
        log.error("stt.provider-unavailable code={} status={}", code, statusCode);
        throw ApiException.contractError(
                ErrorCode.STT_UNAVAILABLE, "지금은 음성 인식을 사용할 수 없어요.", 503);
    }

    /**
     * OpenRouterClient.logProviderHttpFailure와 같은 취지 - 인증/제출/폴링 HTTP 실패에 공통 태그
     * (rtzr-http.failed)를 붙여 Grafana에서 `|= "rtzr-http.failed"` 하나로 모든 Rtzr HTTP 실패를 모아
     * 볼 수 있게 한다. Rtzr 응답 본문은 인증 토큰을 포함하지 않으므로(토큰은 헤더에만) 잘라 남긴다.
     */
    private void logRtzrHttpFailure(String context, int statusCode, byte[] responseBody) {
        String bodySnippet = new String(responseBody, StandardCharsets.UTF_8);
        if (bodySnippet.length() > FAILURE_BODY_LOG_LIMIT) {
            bodySnippet = bodySnippet.substring(0, FAILURE_BODY_LOG_LIMIT) + "...(truncated)";
        }
        log.warn(
                "rtzr-http.failed context={} status={} retryable={} responseBody={}",
                context, statusCode, statusCode >= 429, bodySnippet);
    }

    /**
     * 200 응답 안에서 status="failed"를 낸 케이스 전용 태그 - HTTP 실패와 원인이 다르고(서버 쪽 처리
     * 실패) 사용자 재시도로 잘 안 풀리므로, Grafana에서 별도 규칙으로 관찰 가능하도록 태그를 분리한다.
     */
    private void logRtzrTranscriptionFailed(String submissionId, byte[] responseBody) {
        String bodySnippet = new String(responseBody, StandardCharsets.UTF_8);
        if (bodySnippet.length() > FAILURE_BODY_LOG_LIMIT) {
            bodySnippet = bodySnippet.substring(0, FAILURE_BODY_LOG_LIMIT) + "...(truncated)";
        }
        log.warn("rtzr-transcription.failed submissionId={} responseBody={}", submissionId, bodySnippet);
    }

    private JsonNode safeJson(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static byte[] buildMultipartBody(
            String boundary, byte[] audio, String extension, String mimeType, String configJson) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        String crlf = "\r\n";
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"question." + extension + "\"" + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + mimeType + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(audio);
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"config\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(configJson.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
