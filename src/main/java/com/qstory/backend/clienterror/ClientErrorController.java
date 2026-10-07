package com.qstory.backend.clienterror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저에서 난 에러 한 건을 받아 서버 로그(client.error)로 남긴다 - 그 줄이 Grafana Loki로 가서
 * 알림·대시보드에 잡힌다(ops/grafana-alerting). DB에는 저장하지 않는다.
 *
 * 아이 말이나 연락처가 섞이지 않게 정해진 필드만 받고, 주소의 쿼리·숫자열·이메일은 지운다.
 * 한 기기가 에러를 쏟아내도 로그가 넘치지 않게 서버 전체에서 분당 상한을 둔다(넘으면 조용히 버림).
 */
@Tag(name = "Client errors", description = "Browser-side errors forwarded to server logs for alerting")
@RestController
public class ClientErrorController {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorController.class);

    static final int MAX_PER_MINUTE = 120;
    private static final long MAX_PAYLOAD_BYTES = 8_192;
    private static final Set<String> KINDS =
            Set.of("WINDOW_ERROR", "UNHANDLED_REJECTION", "PLAYBACK", "RUNTIME_FAILURE", "NETWORK", "RENDER");
    private static final Pattern QUERY = Pattern.compile("\\?[^\\s)]*");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{6,}");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._:/\\-]{1,80}");

    private final ObjectMapper objectMapper;
    private final AtomicLong windowStartedAt = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger windowCount = new AtomicInteger();

    public ClientErrorController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "Report one browser error",
            description = "Logs a sanitized one-line `client.error` entry. Always 202 for a well-formed body, "
                    + "including when the server-wide rate cap drops it. Capped at 8KB.")
    @PostMapping("/v1/client-errors")
    public void report(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = readJson(request);
        String kind = body.path("kind").asText("");
        if (!KINDS.contains(kind)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "요청 형식이 올바르지 않아요.");
        }
        if (allow()) {
            log.warn(
                    "client.error kind={} route={} story_id={} scene_id={} release={} browser={} message={} stack={}",
                    kind,
                    token(body.path("route").asText(""), "unknown"),
                    token(body.path("story_id").asText(""), "-"),
                    token(body.path("scene_id").asText(""), "-"),
                    token(body.path("release").asText(""), "-"),
                    token(body.path("browser").asText(""), "-"),
                    clean(body.path("message").asText(""), 300),
                    clean(body.path("stack").asText(""), 1_200));
        }
        HttpJsonWriter.writeJson(response, objectMapper, 202, Map.of("ok", true));
    }

    /** 1분 창마다 MAX_PER_MINUTE건까지만 로그로 남긴다. */
    boolean allow() {
        long now = System.currentTimeMillis();
        long started = windowStartedAt.get();
        if (now - started >= 60_000 && windowStartedAt.compareAndSet(started, now)) {
            windowCount.set(0);
        }
        return windowCount.incrementAndGet() <= MAX_PER_MINUTE;
    }

    /** 라벨처럼 쓰는 짧은 값 - 정해진 문자만 허용하고 아니면 기본값. 주소는 쿼리를 떼고 본다. */
    static String token(String value, String fallback) {
        String trimmed = QUERY.matcher(value.trim()).replaceAll("");
        return SAFE_TOKEN.matcher(trimmed).matches() ? trimmed : fallback;
    }

    /** 자유 문장 - 한 줄로 펴고, 주소 쿼리·이메일·긴 숫자열을 지운 뒤 길이를 자른다. */
    static String clean(String value, int limit) {
        String text = value.replaceAll("[\\r\\n\\t]+", " | ");
        text = QUERY.matcher(text).replaceAll("?[query]");
        text = EMAIL.matcher(text).replaceAll("[email]");
        text = DIGIT_RUN.matcher(text).replaceAll("[number]");
        text = text.trim();
        if (text.isEmpty()) return "-";
        return text.length() > limit ? text.substring(0, limit) + "…" : text;
    }

    private JsonNode readJson(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_PAYLOAD_BYTES) {
            throw ApiException.contractError(ErrorCode.PAYLOAD_TOO_LARGE, "요청이 너무 커요.", 413);
        }
        byte[] body = HttpBodyReader.readAllBytes(
                request.getInputStream(), MAX_PAYLOAD_BYTES, ErrorCode.PAYLOAD_TOO_LARGE, "요청이 너무 커요.");
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (IOException malformed) {
            throw ApiException.contractError(ErrorCode.INVALID_JSON, "요청 형식이 올바르지 않아요.");
        }
        if (node == null || !node.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_JSON, "요청 형식이 올바르지 않아요.");
        }
        return node;
    }
}
