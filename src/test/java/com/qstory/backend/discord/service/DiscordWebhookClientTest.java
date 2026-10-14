package com.qstory.backend.discord.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 429는 retry_after만큼 쉬고 다시(최대 3번), 4xx는 다시 보내지 않고, 연결 실패는 한 번만 다시. */
@SuppressWarnings("unchecked")
class DiscordWebhookClientTest {

    private final HttpClient http = mock(HttpClient.class);
    private final List<Long> sleeps = new ArrayList<>();
    private final DiscordWebhookClient client = new DiscordWebhookClient(http, new ObjectMapper(), sleeps::add);

    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return response;
    }

    @Test
    void retriesAfterRateLimitUsingRetryAfter() throws Exception {
        HttpResponse<String> limited = response(429, "{\"retry_after\": 1.5}");
        HttpResponse<String> ok = response(204, "");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(limited, ok);

        assertTrue(client.post("https://discord.test/hook", "{}", "news"));
        assertEquals(List.of(1500L), sleeps);
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void givesUpAfterMaxRateLimitRetries() throws Exception {
        HttpResponse<String> limited = response(429, "{\"retry_after\": 0.2}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(limited);

        assertFalse(client.post("https://discord.test/hook", "{}", "news"));
        verify(http, times(DiscordWebhookClient.MAX_RETRIES + 1))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void clientErrorIsNotRetried() throws Exception {
        HttpResponse<String> bad = response(400, "{}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(bad);

        assertFalse(client.post("https://discord.test/hook", "{}", "news"));
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void networkFailureRetriesOnceThenReturnsFalse() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("down"));

        assertFalse(client.post("https://discord.test/hook", "{}", "news"));
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void retryAfterIsCapped() {
        assertEquals(DiscordWebhookClient.MAX_WAIT_MILLIS,
                client.retryAfterMillis(response(429, "{\"retry_after\": 999}")));
    }
}
