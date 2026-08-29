package com.qstory.backend.provider.rtzr.util;
import com.qstory.backend.provider.rtzr.RtzrTranscriptionResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.error.AbortException;
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
import org.springframework.stereotype.Component;

/** rtzr-stt.mjs를 Java로 포팅한 것: 토큰을 캐싱하는 인증, 멀티파트 제출, 결과 폴링. */
@Component
public class RtzrSttClient {

    private static final String BASE_URL = "https://openapi.vito.ai";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(1_500);

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
            return poll(accessToken, submissionId, deadline);
        } catch (ProviderException | AbortException known) {
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
            throw new ProviderException(
                    ProviderErrorCode.RTZR_SUBMIT_FAILED, "녹음을 음성 인식기에 전달하지 못했어요.", response.statusCode() >= 429);
        }
        return payload.get("id").asText();
    }

    private RtzrTranscriptionResult poll(String accessToken, String submissionId, RequestDeadline deadline) throws Exception {
        while (true) {
            deadline.requireTimeRemaining();
            Thread.sleep(POLL_INTERVAL.toMillis());
            HttpRequest request = deadline.applyTo(HttpRequest.newBuilder(
                                URI.create(BASE_URL + "/v1/transcribe/" + java.net.URLEncoder.encode(submissionId, StandardCharsets.UTF_8)))
                            .header("accept", "application/json")
                            .header("authorization", "Bearer " + accessToken)
                            .GET())
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode result = safeJson(response.body());
            if (response.statusCode() / 100 != 2) {
                throw new ProviderException(
                        ProviderErrorCode.RTZR_RESULT_FAILED, "음성 인식 결과를 가져오지 못했어요.", response.statusCode() >= 429);
            }
            String status = result != null && result.hasNonNull("status") ? result.get("status").asText() : null;
            if ("failed".equals(status)) {
                // 호출부가 retryable을 생략하면 Node의 ProviderError는 기본값을 true로 둔다 - 아래의
                // throw도 retryable을 생략하므로, 아래 2-인자 팩토리를 통해 ProviderErrorCode.RTZR_TRANSCRIPTION_FAILED의
                // 기본값(true)을 그대로 물려받아 원본과 정확히 동일하게 동작한다.
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
