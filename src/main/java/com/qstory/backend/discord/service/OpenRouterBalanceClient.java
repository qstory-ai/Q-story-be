package com.qstory.backend.discord.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenRouter 남은 잔액(달러) = GET /api/v1/credits의 total_credits - total_usage. 키가 없거나 응답을 못 읽으면 비어 있다.
 * 키는 로그에 찍지 않는다.
 */
@Component
public class OpenRouterBalanceClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterBalanceClient.class);
    static final URI CREDITS_URL = URI.create("https://openrouter.ai/api/v1/credits");

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public OpenRouterBalanceClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        this.http = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = config.providers().openRouter() == null ? null : config.providers().openRouter().apiKey();
    }

    public OptionalDouble remainingUsd() {
        if (apiKey == null || apiKey.isBlank()) {
            return OptionalDouble.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(CREDITS_URL)
                    .timeout(Duration.ofSeconds(10))
                    .header("authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("openrouter.credits-failed status={}", response.statusCode());
                return OptionalDouble.empty();
            }
            return parse(objectMapper.readTree(response.body()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return OptionalDouble.empty();
        } catch (Exception failure) {
            log.warn("openrouter.credits-failed reason={}", failure.getClass().getSimpleName());
            return OptionalDouble.empty();
        }
    }

    static OptionalDouble parse(JsonNode root) {
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.path("total_credits").isNumber() || !data.path("total_usage").isNumber()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(data.path("total_credits").asDouble() - data.path("total_usage").asDouble());
    }
}
