package com.qstory.backend.finops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Railway 이번 결제 기간 사용액 - 공개 GraphQL API의 workspace.customer(currentUsage·billingPeriod). 토큰이나 워크스페이스
 * ID가 없으면 비어 있다. 토큰은 로그에 찍지 않는다.
 */
@Component
public class RailwayUsageClient {

    private static final Logger log = LoggerFactory.getLogger(RailwayUsageClient.class);
    static final URI API = URI.create("https://backboard.railway.com/graphql/v2");
    static final String QUERY =
            "query($w:String!){ workspace(workspaceId:$w){ customer{ currentUsage billingPeriod{ start end } } } }";

    public record Usage(double currentUsd, OffsetDateTime periodStart, OffsetDateTime periodEnd) {}

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final String token;
    private final String workspaceId;

    public RailwayUsageClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            @Value("${qstory.finops.railway-api-token:}") String token,
            @Value("${qstory.finops.railway-workspace-id:}") String workspaceId) {
        this.http = httpClient;
        this.objectMapper = objectMapper;
        this.token = token;
        this.workspaceId = workspaceId;
    }

    public Optional<Usage> fetch() {
        if (token == null || token.isBlank() || workspaceId == null || workspaceId.isBlank()) return Optional.empty();
        try {
            String body = objectMapper.writeValueAsString(Map.of("query", QUERY, "variables", Map.of("w", workspaceId)));
            HttpRequest request = HttpRequest.newBuilder(API)
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + token)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("finops.railway-usage-failed status={}", response.statusCode());
                return Optional.empty();
            }
            Optional<Usage> usage = parse(objectMapper.readTree(response.body()));
            if (usage.isEmpty()) log.warn("finops.railway-usage-failed reason=no-data");
            return usage;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception failure) {
            log.warn("finops.railway-usage-failed reason={}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static Optional<Usage> parse(JsonNode response) {
        JsonNode customer = response.path("data").path("workspace").path("customer");
        if (!customer.path("currentUsage").isNumber()) return Optional.empty();
        JsonNode period = customer.path("billingPeriod");
        return Optional.of(new Usage(customer.path("currentUsage").asDouble(), time(period.path("start")),
                time(period.path("end"))));
    }

    private static OffsetDateTime time(JsonNode node) {
        return node.isTextual() ? OffsetDateTime.parse(node.asText()) : null;
    }
}
