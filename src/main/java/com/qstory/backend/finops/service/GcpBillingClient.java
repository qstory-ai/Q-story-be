package com.qstory.backend.finops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.qstory.backend.analytics.service.GaDataClient;
import com.qstory.backend.config.GaProperties;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 구글 클라우드 실제 청구액 - 결제 계정이 BigQuery(서비스 계정 프로젝트의 billing_export 데이터셋)로 내보내는 표준 사용 비용을
 * 날짜(미국 태평양)·서비스·SKU별로 읽는다. GA 가져오기와 같은 서비스 계정 키를 쓴다. 키가 없거나 내보내기 표가 아직 없으면 비어 있다.
 */
@Component
public class GcpBillingClient {

    private static final Logger log = LoggerFactory.getLogger(GcpBillingClient.class);
    static final String SCOPE = "https://www.googleapis.com/auth/bigquery.readonly";
    static final String DATASET = "billing_export";
    static final String TABLE_PREFIX = "gcp_billing_export_v1_";
    static final String QUERY = """
            select format_date('%%F', date(usage_start_time, 'America/Los_Angeles')) as usage_date,
                   service.description as service, sku.description as sku, currency,
                   sum(cost + ifnull((select sum(c.amount) from unnest(credits) c), 0)) as cost,
                   sum((cost + ifnull((select sum(c.amount) from unnest(credits) c), 0)) / nullif(currency_conversion_rate, 0)) as cost_usd
            from `%s.%s.%s`
            where usage_start_time >= timestamp_sub(current_timestamp(), interval %d day)
            group by 1, 2, 3, 4
            """;

    public record Row(LocalDate usageDate, String service, String sku, String currency, double cost, Double costUsd) {}

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final GoogleCredentials credentials;
    private final String projectId;

    public GcpBillingClient(HttpClient httpClient, ObjectMapper objectMapper, GaProperties ga) {
        this.http = httpClient;
        this.objectMapper = objectMapper;
        GoogleCredentials creds = null;
        String project = null;
        String json = ga == null || ga.serviceAccountJson() == null || ga.serviceAccountJson().isBlank()
                ? null : GaDataClient.decodeServiceAccount(ga.serviceAccountJson());
        if (json != null) {
            try {
                project = objectMapper.readTree(json).path("project_id").asText(null);
                creds = ServiceAccountCredentials
                        .fromStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                        .createScoped(List.of(SCOPE));
            } catch (Exception invalid) {
                log.warn("finops.gcp-billing-disabled reason={}", invalid.getClass().getSimpleName());
            }
        }
        this.credentials = creds;
        this.projectId = project;
    }

    /** 최근 days일 청구 행. 내보내기 전이거나 실패하면 비어 있다. */
    public Optional<List<Row>> fetch(int days) {
        if (credentials == null || projectId == null) return Optional.empty();
        try {
            String base = "https://bigquery.googleapis.com/bigquery/v2/projects/" + projectId;
            String table = null;
            for (JsonNode t : call(base + "/datasets/" + DATASET + "/tables", null).path("tables")) {
                String id = t.path("tableReference").path("tableId").asText("");
                if (id.startsWith(TABLE_PREFIX)) table = id;
            }
            if (table == null) {
                log.info("finops.gcp-billing-not-ready");
                return Optional.empty();
            }
            String sql = QUERY.formatted(projectId, DATASET, table, days);
            JsonNode result = call(base + "/queries", Map.of("query", sql, "useLegacySql", false, "timeoutMs", 30000));
            if (!result.path("jobComplete").asBoolean(false)) {
                log.warn("finops.gcp-billing-failed reason=timeout");
                return Optional.empty();
            }
            return Optional.of(parse(result));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception failure) {
            log.warn("finops.gcp-billing-failed reason={}", failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            return Optional.empty();
        }
    }

    private JsonNode call(String url, Object body) throws Exception {
        String token;
        synchronized (credentials) {
            credentials.refreshIfExpired();
            token = credentials.getAccessToken().getTokenValue();
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("authorization", "Bearer " + token);
        if (body == null) {
            request.GET();
        } else {
            request.header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("status=" + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    /** jobs.query 응답 rows(f[].v, 열 순서는 QUERY와 같다). 비용이 비어 있는 행은 건너뛴다. */
    static List<Row> parse(JsonNode result) {
        List<Row> rows = new ArrayList<>();
        for (JsonNode row : result.path("rows")) {
            JsonNode f = row.path("f");
            String cost = f.path(4).path("v").asText(null);
            if (cost == null) continue;
            String usd = f.path(5).path("v").asText(null);
            rows.add(new Row(LocalDate.parse(f.path(0).path("v").asText()), f.path(1).path("v").asText(""),
                    f.path(2).path("v").asText(""), f.path(3).path("v").asText(""), Double.parseDouble(cost),
                    usd == null ? null : Double.parseDouble(usd)));
        }
        return rows;
    }
}
