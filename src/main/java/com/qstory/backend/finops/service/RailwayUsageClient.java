package com.qstory.backend.finops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Railway 이번 결제 기간 사용액 - 프로젝트 토큰으로 공개 GraphQL의 usage(지금까지)·estimatedUsage(기간 끝 예상) 자원량을 받아
 * Railway 공개 단가로 달러를 낸다(vCPU 한 달(730시간) $20, 메모리 GB 한 달 $10, 내보내기 GB당 $0.05 - CLI `railway usage`의
 * 청구액과 같게 나온다). 토큰이 없으면 비어 있다. 토큰은 로그에 찍지 않는다.
 */
@Component
public class RailwayUsageClient {

    private static final Logger log = LoggerFactory.getLogger(RailwayUsageClient.class);
    static final URI API = URI.create("https://backboard.railway.com/graphql/v2");
    static final String PROJECT_QUERY = "{ projectToken { projectId } }";
    static final String USAGE_QUERY = """
            query($p:String!){
              usage(projectId:$p, measurements:[CPU_USAGE, MEMORY_USAGE_GB, NETWORK_TX_GB]){ measurement value }
              estimatedUsage(projectId:$p, measurements:[CPU_USAGE, MEMORY_USAGE_GB, NETWORK_TX_GB]){ measurement estimatedValue }
            }
            """;
    private static final double MINUTES_PER_MONTH = 730 * 60;
    static final Map<String, Double> USD_PER_UNIT = Map.of(
            "CPU_USAGE", 20 / MINUTES_PER_MONTH,
            "MEMORY_USAGE_GB", 10 / MINUTES_PER_MONTH,
            "NETWORK_TX_GB", 0.05);

    public record Usage(double currentUsd, double projectedUsd) {}

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final String token;

    public RailwayUsageClient(
            HttpClient httpClient, ObjectMapper objectMapper, @Value("${qstory.finops.railway-api-token:}") String token) {
        this.http = httpClient;
        this.objectMapper = objectMapper;
        this.token = token;
    }

    public Optional<Usage> fetch() {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            String projectId = post(PROJECT_QUERY, Map.of()).path("data").path("projectToken").path("projectId").asText(null);
            if (projectId == null) {
                log.warn("finops.railway-usage-failed reason=no-project");
                return Optional.empty();
            }
            Optional<Usage> usage = parse(post(USAGE_QUERY, Map.of("p", projectId)));
            if (usage.isEmpty()) log.warn("finops.railway-usage-failed reason=no-data");
            return usage;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception failure) {
            log.warn("finops.railway-usage-failed reason={}", failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            return Optional.empty();
        }
    }

    private JsonNode post(String query, Map<String, Object> variables) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("query", query, "variables", variables));
        HttpRequest request = HttpRequest.newBuilder(API)
                .timeout(Duration.ofSeconds(15))
                .header("project-access-token", token)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("status=" + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    static Optional<Usage> parse(JsonNode response) {
        JsonNode data = response.path("data");
        if (!data.path("usage").isArray() || !data.path("estimatedUsage").isArray()) return Optional.empty();
        return Optional.of(new Usage(dollars(data.path("usage"), "value"), dollars(data.path("estimatedUsage"), "estimatedValue")));
    }

    private static double dollars(JsonNode measurements, String field) {
        double total = 0;
        for (JsonNode m : measurements) {
            total += m.path(field).asDouble(0) * USD_PER_UNIT.getOrDefault(m.path("measurement").asText(), 0.0);
        }
        return total;
    }
}
