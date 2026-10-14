package com.qstory.backend.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.config.GaProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * GA Data API v1beta runReport 호출. 페이지(10,000행)를 넘겨 가며 전부 받는다. 서비스 계정 키가 없거나 읽을 수
 * 없으면 enabled()가 false이고 부팅 때 ga-import.disabled 한 줄만 남긴다. 로그·예외 메시지에 키나 토큰을 넣지 않는다.
 */
@Component
public class GaDataClient {

    private static final Logger log = LoggerFactory.getLogger(GaDataClient.class);
    static final String BASE_URL = "https://analyticsdata.googleapis.com/v1beta/properties/";
    static final int PAGE_SIZE = 10_000;
    private static final int MAX_PAGES = 50;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String propertyId;
    private final GaAccessTokenProvider tokens;

    @Autowired
    public GaDataClient(HttpClient httpClient, ObjectMapper objectMapper, GaProperties properties) {
        this(httpClient, objectMapper, setupPropertyId(properties), setupTokens(properties));
    }

    /** 테스트용 - propertyId나 tokens가 null이면 꺼진 클라이언트. */
    GaDataClient(HttpClient httpClient, ObjectMapper objectMapper, String propertyId, GaAccessTokenProvider tokens) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.propertyId = propertyId;
        this.tokens = tokens;
    }

    public boolean enabled() {
        return propertyId != null && tokens != null;
    }

    public List<GaRows.EventRow> events(LocalDate start, LocalDate end) throws GaApiException {
        return fetchAll(GaReport.EVENTS, start, end, GaReportParser::events);
    }

    public List<GaRows.TrafficRow> traffic(LocalDate start, LocalDate end) throws GaApiException {
        return fetchAll(GaReport.TRAFFIC, start, end, GaReportParser::traffic);
    }

    private <T> List<T> fetchAll(
            GaReport report, LocalDate start, LocalDate end, Function<JsonNode, GaReportParser.Page<T>> parse)
            throws GaApiException {
        if (!enabled()) {
            throw new GaApiException("disabled");
        }
        List<T> all = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            GaReportParser.Page<T> result = parse.apply(call(requestBody(report, start, end, offset)));
            all.addAll(result.rows());
            offset += result.rawRows();
            if (result.rawRows() == 0 || offset >= result.rowCount()) {
                return all;
            }
        }
        throw new GaApiException("too-many-pages report=" + report);
    }

    ObjectNode requestBody(GaReport report, LocalDate start, LocalDate end, int offset) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode range = body.putArray("dateRanges").addObject();
        range.put("startDate", start.toString());
        range.put("endDate", end.toString());
        ArrayNode dimensions = body.putArray("dimensions");
        report.dimensions().forEach(name -> dimensions.addObject().put("name", name));
        ArrayNode metrics = body.putArray("metrics");
        report.metrics().forEach(name -> metrics.addObject().put("name", name));
        body.put("limit", PAGE_SIZE);
        body.put("offset", offset);
        return body;
    }

    private JsonNode call(ObjectNode body) throws GaApiException {
        String accessToken;
        try {
            accessToken = tokens.accessToken();
        } catch (IOException | RuntimeException error) {
            throw new GaApiException("auth " + error.getClass().getSimpleName());
        }
        HttpResponse<byte[]> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + propertyId + ":runReport"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GaApiException("interrupted");
        } catch (IOException | RuntimeException error) {
            throw new GaApiException("network " + error.getClass().getSimpleName());
        }
        if (response.statusCode() / 100 != 2) {
            throw new GaApiException("http-" + response.statusCode() + " " + errorStatus(response.body()));
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException | RuntimeException unreadable) {
            throw new GaApiException("unreadable-response");
        }
    }

    /** 오류 본문 {"error":{"code":403,"message":"...","status":"PERMISSION_DENIED"}}에서 상태와 메시지 앞부분만. */
    private String errorStatus(byte[] body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String message = error.path("message").asText("");
            if (message.length() > 160) {
                message = message.substring(0, 160);
            }
            return error.path("status").asText("-") + " " + message;
        } catch (IOException | RuntimeException unreadable) {
            return "-";
        }
    }

    private static String setupPropertyId(GaProperties properties) {
        if (properties == null || !properties.enabled()) {
            return null;
        }
        String id = properties.propertyId().trim();
        if (id.startsWith("properties/")) {
            id = id.substring("properties/".length());
        }
        if (!id.matches("[0-9]{1,20}")) {
            log.warn("ga-import.disabled reason=invalid-property-id");
            return null;
        }
        return id;
    }

    private static GaAccessTokenProvider setupTokens(GaProperties properties) {
        if (properties == null || !properties.enabled()) {
            boolean killed = properties != null && Boolean.FALSE.equals(properties.importEnabled());
            log.info("ga-import.disabled reason={}", killed ? "kill-switch" : "not-configured");
            return null;
        }
        String json = decodeServiceAccount(properties.serviceAccountJson());
        if (json == null) {
            log.warn("ga-import.disabled reason=invalid-service-account detail=not-json-or-base64");
            return null;
        }
        try {
            GaAccessTokenProvider provider = new GoogleGaAccessTokenProvider(json);
            log.info("ga-import.enabled");
            return provider;
        } catch (IOException | RuntimeException invalid) {
            log.warn("ga-import.disabled reason=invalid-service-account detail={}", invalid.getClass().getSimpleName());
            return null;
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

    /** GA 호출 실패. 메시지는 짧은 사유(상태 코드·GA 오류 상태)만 - 키·토큰은 넣지 않는다. */
    public static class GaApiException extends Exception {
        public GaApiException(String reason) {
            super(reason);
        }
    }
}
