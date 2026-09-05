package com.qstory.backend.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Server-side confirmation only: Toss's secret key never reaches a browser bundle. */
@Component
public class TossPaymentsClient {
    private static final URI CONFIRM_URI = URI.create("https://api.tosspayments.com/v1/payments/confirm");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties config;

    public TossPaymentsClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public Approval confirm(String paymentKey, String orderId, int amount) {
        if (config.payments() == null || config.payments().toss() == null || !config.payments().toss().configured()) {
            throw ApiException.contractError(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "결제 설정이 아직 준비되지 않았어요.", 503);
        }
        try {
            byte[] payload = objectMapper.createObjectNode()
                    .put("paymentKey", paymentKey)
                    .put("orderId", orderId)
                    .put("amount", amount)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            String credentials = Base64.getEncoder().encodeToString(
                    (config.payments().toss().secretKey() + ":").getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(CONFIRM_URI)
                    .timeout(Duration.ofSeconds(20))
                    .header("authorization", "Basic " + credentials)
                    .header("content-type", "application/json")
                    .header("idempotency-key", "qstory-" + orderId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw ApiException.contractError(ErrorCode.PAYMENT_CONFIRMATION_FAILED, "결제 승인을 확인하지 못했어요. 결제 내역을 확인한 뒤 다시 시도해 주세요.", 422);
            }
            JsonNode body = objectMapper.readTree(response.body());
            if (!"DONE".equals(body.path("status").asText())) {
                throw ApiException.contractError(ErrorCode.PAYMENT_CONFIRMATION_FAILED, "결제가 완료되지 않았어요.", 422);
            }
            String approvedAt = body.path("approvedAt").asText(null);
            return new Approval(approvedAt == null ? Instant.now() : Instant.parse(approvedAt));
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.contractError(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "결제 확인이 지연되고 있어요. 잠시 후 다시 시도해 주세요.", 503);
        } catch (Exception exception) {
            throw ApiException.contractError(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "결제 확인에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.", 503);
        }
    }

    public record Approval(Instant approvedAt) {}
}
