package com.qstory.backend.provider.higgsfield.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.config.RealtimeImageProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Higgsfield 이미지 API(GPT Image 2.5 Flare, marketing-studio/image/flare). fe/scripts/illustrations/generate-higgsfield.mjs와
 * 같은 호출 순서다: 제출(비동기, request_id·status_url) → 상태를 지켜봄(completed/failed/nsfw/canceled) → 결과 그림 내려받기
 * (결과 주소는 7일만 살아 있어 바로 받아 Supabase에 올린다).
 *
 * <p>Idempotency-Key를 같이 보내면 같은 요청을 다시 보내도 새로 과금되지 않는다(서버 재시작으로 끊긴 작업을 이어갈 때).
 */
@Component
public class HiggsfieldImageClient {

    private static final Logger log = LoggerFactory.getLogger(HiggsfieldImageClient.class);
    private static final String API = "https://api.higgsfield.ai";
    private static final String ENDPOINT = "/marketing-studio/image/flare";
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "nsfw", "canceled");
    private static final long MAX_IMAGE_BYTES = 30L * 1024 * 1024;
    private static final int FAILURE_BODY_LOG_LIMIT = 300;

    public record Submitted(String requestId, String statusUrl, String status) {}

    /** 상태 한 번 조회한 결과. terminal()이면 더 묻지 않는다. */
    public record Status(String status, String imageUrl, String error) {
        public boolean terminal() {
            return TERMINAL.contains(status);
        }

        public boolean completed() {
            return "completed".equals(status) && imageUrl != null;
        }
    }

    public record Image(byte[] bytes, String mimeType, String requestId) {}

    /** 제출·조회·내려받기 어디서든 실패하면 이 예외 하나로 올린다 - 호출자는 그림 없이 진행한다. */
    public static class HiggsfieldException extends RuntimeException {
        private final String code;

        public HiggsfieldException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RealtimeImageProperties properties;

    public HiggsfieldImageClient(HttpClient httpClient, ObjectMapper objectMapper, RealtimeImageProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 제출하고 끝날 때까지 기다려 그림 바이트를 돌려준다. timeoutMs를 넘기면 HiggsfieldException(TIMEOUT). */
    public Image generate(String prompt, List<String> referenceImageUrls, String idempotencyKey, long timeoutMs) {
        Submitted submitted = submit(prompt, referenceImageUrls, idempotencyKey);
        long startedAt = System.currentTimeMillis();
        long wait = 3_000;
        Status status = new Status(submitted.status() == null ? "queued" : submitted.status(), null, null);
        while (!status.terminal()) {
            if (System.currentTimeMillis() - startedAt > timeoutMs) {
                throw new HiggsfieldException("TIMEOUT", "request " + submitted.requestId() + " did not finish in " + timeoutMs + "ms");
            }
            sleep(wait);
            wait = Math.min((long) (wait * 1.5), 10_000);
            status = status(submitted);
        }
        if (!status.completed()) {
            throw new HiggsfieldException(status.status().toUpperCase(), status.error() == null ? "no image" : status.error());
        }
        byte[] bytes = download(status.imageUrl());
        String mimeType = detectMimeType(bytes);
        if (mimeType == null) {
            throw new HiggsfieldException("INVALID_IMAGE", "unknown image format");
        }
        return new Image(bytes, mimeType, submitted.requestId());
    }

    Submitted submit(String prompt, List<String> referenceImageUrls, String idempotencyKey) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", prompt);
        ArrayNode urls = body.putArray("image_urls");
        referenceImageUrls.stream().limit(16).forEach(urls::add);
        body.put("resolution", properties.resolution());
        body.put("aspect_ratio", "16:9");
        body.put("quality", properties.quality());
        body.put("enhance_prompt", false);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(API + ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("authorization", authorization())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toString().getBytes(StandardCharsets.UTF_8)));
        if (idempotencyKey != null) {
            request.header("idempotency-key", idempotencyKey);
        }
        JsonNode json = sendJson(request.build(), "submit");
        String requestId = json.path("request_id").asText(null);
        if (requestId == null) {
            throw new HiggsfieldException("NO_REQUEST_ID", "submit returned no request_id");
        }
        return new Submitted(requestId, json.path("status_url").asText(null), json.path("status").asText(null));
    }

    Status status(Submitted submitted) {
        String url = submitted.statusUrl() != null ? submitted.statusUrl() : API + "/requests/" + submitted.requestId() + "/status";
        JsonNode json = sendJson(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("authorization", authorization())
                .GET().build(), "status");
        return parseStatus(json);
    }

    public static Status parseStatus(JsonNode json) {
        String status = json.path("status").asText("queued");
        String imageUrl = json.path("images").path(0).path("url").asText(null);
        JsonNode error = json.path("error");
        String errorText = error.isMissingNode() || error.isNull() ? null : error.isTextual() ? error.asText() : error.toString();
        return new Status(status, imageUrl, errorText);
    }

    byte[] download(String url) {
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new HiggsfieldException("DOWNLOAD_HTTP_" + response.statusCode(), "could not download result");
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length < 1_000 || bytes.length > MAX_IMAGE_BYTES) {
                throw new HiggsfieldException("DOWNLOAD_SIZE", "result size " + (bytes == null ? 0 : bytes.length));
            }
            return bytes;
        } catch (IOException error) {
            throw new HiggsfieldException("DOWNLOAD_NETWORK", error.toString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HiggsfieldException("INTERRUPTED", "download interrupted");
        }
    }

    private JsonNode sendJson(HttpRequest request, String stage) {
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if ((code == 429 || code >= 500) && attempt < 3) {
                    sleep(2_000L * (attempt + 1));
                    continue;
                }
                if (code / 100 != 2) {
                    String body = response.body() == null ? "" : response.body();
                    log.warn("higgsfield.{}.failed status={} body={}", stage, code,
                            body.substring(0, Math.min(body.length(), FAILURE_BODY_LOG_LIMIT)));
                    throw new HiggsfieldException("HTTP_" + code, stage + " failed");
                }
                return objectMapper.readTree(response.body());
            } catch (IOException error) {
                if (attempt < 3) {
                    sleep(2_000L * (attempt + 1));
                    continue;
                }
                throw new HiggsfieldException("NETWORK", stage + ": " + error);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new HiggsfieldException("INTERRUPTED", stage + " interrupted");
            }
        }
    }

    private String authorization() {
        return "Key " + properties.apiKeyId() + ":" + properties.apiKeySecret();
    }

    static String detectMimeType(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HiggsfieldException("INTERRUPTED", "wait interrupted");
        }
    }
}
