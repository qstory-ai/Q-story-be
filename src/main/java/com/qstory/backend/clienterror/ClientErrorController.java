package com.qstory.backend.clienterror;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.log.LogEvents;
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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저에서 난 에러 한 건을 받아 서버 로그(client.error)로 남긴다 - 그 줄이 Grafana Loki로 가서
 * 알림·대시보드에 잡힌다(ops/grafana-alerting). DB에는 저장하지 않는다.
 *
 * 아이 말이나 연락처가 섞이지 않게 정해진 필드만 받고, 주소의 쿼리·숫자열·이메일은 지운다. 값 안의 '='는 바꿔 써서
 * 한 줄 로그(key=value)에 가짜 필드를 끼워 넣지 못하게 한다.
 * 한 기기가 에러를 쏟아내도 로그가 넘치지 않게 RateLimitFilter가 보낸 주소마다 분당 상한(client-errors)과 서버 전체
 * 상한(client-errors-global)을 둔다(보안 점검 L10 - 한 곳이 상한을 다 써서 다른 사용자의 에러가 묻히지 않게).
 */
@Tag(name = "Client errors", description = "Browser-side errors forwarded to server logs for alerting")
@RestController
public class ClientErrorController {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorController.class);

    private static final long MAX_PAYLOAD_BYTES = 8_192;
    private static final Set<String> KINDS =
            Set.of("WINDOW_ERROR", "UNHANDLED_REJECTION", "PLAYBACK", "RUNTIME_FAILURE", "NETWORK", "RENDER");
    private static final Pattern QUERY = Pattern.compile("\\?[^\\s)]*");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{6,}");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._:/\\-]{1,80}");

    private final ObjectMapper objectMapper;

    public ClientErrorController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "Report one browser error",
            description = "Logs a sanitized one-line `client.error` entry and answers 202. Rate limits (120/min per client, "
                    + "600/min server-wide) are enforced by RateLimitFilter with 429. Capped at 8KB.")
    @PostMapping("/v1/client-errors")
    public void report(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = readJson(request);
        String kind = body.path("kind").asText("");
        if (!KINDS.contains(kind)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "요청 형식이 올바르지 않아요.");
        }
        log.warn(
                LogEvents.CLIENT_ERROR + " kind={} route={} story_id={} scene_id={} release={} browser={} message={} stack={}",
                kind,
                token(body.path("route").asText(""), "unknown"),
                token(body.path("story_id").asText(""), "-"),
                token(body.path("scene_id").asText(""), "-"),
                token(body.path("release").asText(""), "-"),
                token(body.path("browser").asText(""), "-"),
                clean(body.path("message").asText(""), 300),
                clean(body.path("stack").asText(""), 1_200));
        HttpJsonWriter.writeJson(response, objectMapper, 202, Map.of("ok", true));
    }

    /** 라벨처럼 쓰는 짧은 값 - 정해진 문자만 허용하고 아니면 기본값. 주소는 쿼리를 떼고 본다. */
    static String token(String value, String fallback) {
        String trimmed = QUERY.matcher(value.trim()).replaceAll("");
        return SAFE_TOKEN.matcher(trimmed).matches() ? trimmed : fallback;
    }

    /** 자유 문장 - 한 줄로 펴고, 주소 쿼리·이메일·긴 숫자열을 지운 뒤 길이를 자른다. */
    static String clean(String value, int limit) {
        String text = value.replaceAll("[\\r\\n\\t]+", " | ").replace("=", "%3D");
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
