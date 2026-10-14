package com.qstory.backend.analytics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.GaProperties;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GaDataClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = mock(HttpClient.class);
    private final GaDataClient client = new GaDataClient(http, objectMapper, List.of("555537619"), () -> "access-123");

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private static String trafficPage(int rowCount, String... dates) {
        StringBuilder rows = new StringBuilder();
        for (String date : dates) {
            if (!rows.isEmpty()) {
                rows.append(',');
            }
            rows.append("""
                    {"dimensionValues": [{"value": "%s"}, {"value": "demo.qstory.ai.kr"}, {"value": "qr"},
                      {"value": "offline"}, {"value": "qr-main"}, {"value": "web"}, {"value": "app"}],
                     "metricValues": [{"value": "1"}, {"value": "1"}, {"value": "1"}, {"value": "1"}, {"value": "2"}]}
                    """.formatted(date));
        }
        return "{\"rows\": [" + rows + "], \"rowCount\": " + rowCount + "}";
    }

    @Test
    void sendsRunReportWithAllDimensionsAndPagesUntilRowCount() throws Exception {
        HttpResponse<byte[]> first = response(200, trafficPage(3, "20261012", "20261013"));
        HttpResponse<byte[]> second = response(200, trafficPage(3, "20261014"));
        doReturn(first).doReturn(second).when(http).send(any(HttpRequest.class), any());

        List<GaRows.TrafficRow> rows = client.traffic("555537619", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14));

        assertEquals(3, rows.size());
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(captor.capture(), any());
        HttpRequest request = captor.getAllValues().get(0);
        assertEquals("https://analyticsdata.googleapis.com/v1beta/properties/555537619:runReport", request.uri().toString());
        assertEquals("Bearer access-123", request.headers().firstValue("Authorization").orElseThrow());
        JsonNode body = objectMapper.readTree(bodyOf(request));
        assertEquals("2026-10-12", body.path("dateRanges").path(0).path("startDate").asText());
        assertEquals("2026-10-14", body.path("dateRanges").path(0).path("endDate").asText());
        assertEquals(GaReport.TRAFFIC.dimensions().size(), body.path("dimensions").size());
        assertEquals("customUser:qs_platform", body.path("dimensions").path(5).path("name").asText());
        assertEquals("totalUsers", body.path("metrics").path(0).path("name").asText());
        assertEquals(0, body.path("offset").asInt());
        assertEquals(2, objectMapper.readTree(bodyOf(captor.getAllValues().get(1))).path("offset").asInt());
    }

    @Test
    void eventsReportAsksForEventName() {
        JsonNode body = client.requestBody(GaReport.EVENTS, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), 0);

        assertEquals("eventName", body.path("dimensions").path(7).path("name").asText());
        assertEquals("eventCount", body.path("metrics").path(0).path("name").asText());
        assertEquals(GaDataClient.PAGE_SIZE, body.path("limit").asInt());
    }

    @Test
    void errorResponseBecomesShortReasonWithoutToken() throws Exception {
        HttpResponse<byte[]> denied = response(403,
                "{\"error\":{\"code\":403,\"message\":\"User does not have sufficient permissions\",\"status\":\"PERMISSION_DENIED\"}}");
        doReturn(denied).when(http).send(any(HttpRequest.class), any());

        GaDataClient.GaApiException error = assertThrows(GaDataClient.GaApiException.class,
                () -> client.events("555537619", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)));

        assertTrue(error.getMessage().startsWith("http-403 PERMISSION_DENIED"));
        assertFalse(error.getMessage().contains("access-123"));
    }

    @Test
    void missingKeyOrPropertyOrKillSwitchDisablesClient() {
        assertFalse(new GaDataClient(http, objectMapper, new GaProperties("", "555537619", true, 3, 30)).enabled());
        assertFalse(new GaDataClient(http, objectMapper, new GaProperties("{}", "", true, 3, 30)).enabled());
        assertFalse(new GaDataClient(http, objectMapper, new GaProperties("{\"type\":\"service_account\"}", "555537619", false, 3, 30)).enabled());
        assertFalse(new GaDataClient(http, objectMapper, new GaProperties("not-a-key", "555537619", true, 3, 30)).enabled());
        assertFalse(new GaDataClient(http, objectMapper, new GaProperties("{\"type\":\"service_account\"}", "abc", true, 3, 30)).enabled());
        assertThrows(GaDataClient.GaApiException.class, () ->
                new GaDataClient(http, objectMapper, List.of(), null).traffic("1", LocalDate.now(), LocalDate.now()));
        verifyNoInteractions(http);
    }

    @Test
    void severalPropertiesAreSplitOnCommaOrSpaceAndAnyTypoDisablesAll() {
        assertEquals(List.of("555537619", "558917997"), GaDataClient.setupPropertyIds(
                new GaProperties("{}", " 555537619, properties/558917997 555537619", true, 3, 30)));
        assertEquals(List.of(), GaDataClient.setupPropertyIds(new GaProperties("{}", "555537619,G-7PH6EEGDR3", true, 3, 30)));
    }

    @Test
    void propertyWithoutCustomDimensionsIsRetriedWithStandardOnes() throws Exception {
        HttpResponse<byte[]> invalid = response(400, "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\","
                + "\"message\":\"Did you mean firstUserSourcePlatform? Field customUser:qs_platform is not a valid dimension.\"}}");
        HttpResponse<byte[]> ok = response(200, """
                {"dimensionHeaders": [{"name": "date"}, {"name": "hostName"}, {"name": "sessionSource"},
                   {"name": "sessionMedium"}, {"name": "sessionCampaignName"}],
                 "metricHeaders": [{"name": "totalUsers"}, {"name": "newUsers"}, {"name": "sessions"},
                   {"name": "engagedSessions"}, {"name": "screenPageViews"}],
                 "rows": [{"dimensionValues": [{"value": "20261014"}, {"value": "qstory.ai.kr"}, {"value": "qr"},
                   {"value": "offline"}, {"value": "qr-main"}],
                   "metricValues": [{"value": "2"}, {"value": "2"}, {"value": "2"}, {"value": "1"}, {"value": "2"}]}],
                 "rowCount": 1}
                """);
        doReturn(invalid).doReturn(ok).when(http).send(any(HttpRequest.class), any());

        List<GaRows.TrafficRow> rows = client.traffic("555537619", LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14));

        assertEquals(1, rows.size());
        assertEquals("qr", rows.get(0).key().source());
        assertEquals(GaReportParser.NOT_SET, rows.get(0).key().platform());
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(captor.capture(), any());
        JsonNode retried = objectMapper.readTree(bodyOf(captor.getAllValues().get(1)));
        assertEquals(5, retried.path("dimensions").size());
    }

    @Test
    void unknownPropertyIsRefusedWithoutCalling() {
        assertThrows(GaDataClient.GaApiException.class,
                () -> client.events("999", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)));
        verifyNoInteractions(http);
    }

    @Test
    void serviceAccountMayBeRawJsonOrBase64() {
        String json = "{\"type\":\"service_account\"}";
        assertEquals(json, GaDataClient.decodeServiceAccount("  " + json + "\n"));
        assertEquals(json, GaDataClient.decodeServiceAccount(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
        assertNull(GaDataClient.decodeServiceAccount("plain text"));
    }

    @Test
    void propertiesDefaultsAndBounds() {
        GaProperties defaults = new GaProperties("{}", "1", null, null, null);
        assertTrue(defaults.enabled());
        assertEquals(3, defaults.lookbackDaysOrDefault());
        assertEquals(30, defaults.initialBackfillDaysOrDefault());
        assertEquals(30, new GaProperties("{}", "1", true, 99, 9999).lookbackDaysOrDefault());
        assertEquals(365, new GaProperties("{}", "1", true, 99, 9999).initialBackfillDaysOrDefault());
    }

    private static String bodyOf(HttpRequest request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.join();
        return out.toString(StandardCharsets.UTF_8);
    }
}
