package com.qstory.backend.finops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenRouter 사용액 - GET /api/v1/key(누적·오늘(UTC)·이번 달 사용액)와 /api/v1/credits(충전액). 키가 없거나 응답을 못 읽으면
 * 비어 있다. 키는 로그에 찍지 않는다.
 */
@Component
public class OpenRouterUsageClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterUsageClient.class);
    static final URI KEY_URL = URI.create("https://openrouter.ai/api/v1/key");
    static final URI CREDITS_URL = URI.create("https://openrouter.ai/api/v1/credits");

    public record Usage(Double totalUsd, Double dailyUsd, Double monthlyUsd, Double creditsUsd) {}

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public OpenRouterUsageClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        this.http = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = config.providers().openRouter() == null ? null : config.providers().openRouter().apiKey();
    }

    public Optional<Usage> fetch() {
        if (apiKey == null || apiKey.isBlank()) return Optional.empty();
        try {
            JsonNode key = get(KEY_URL);
            JsonNode credits = get(CREDITS_URL);
            return key == null ? Optional.empty() : Optional.of(parse(key, credits));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception failure) {
            log.warn("finops.openrouter-usage-failed reason={}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private JsonNode get(URI url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(10))
                .header("authorization", "Bearer " + apiKey)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            log.warn("finops.openrouter-usage-failed url={} status={}", url.getPath(), response.statusCode());
            return null;
        }
        return objectMapper.readTree(response.body());
    }

    static Usage parse(JsonNode key, JsonNode credits) {
        JsonNode k = key.path("data");
        JsonNode c = credits == null ? null : credits.path("data");
        return new Usage(number(k, "usage"), number(k, "usage_daily"), number(k, "usage_monthly"),
                c == null ? null : number(c, "total_credits"));
    }

    private static Double number(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }
}
