package com.qstory.backend.discord.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 웹훅 한 번 보내기(동기). 429는 응답의 retry_after(초) 또는 Retry-After 헤더만큼 쉬고 최대 MAX_RETRIES번 다시 보낸다.
 * 5xx·연결 실패는 한 번만 다시. 그 밖의 실패는 로그 한 줄(discord.failed)만 남기고 false - 웹훅 주소는 찍지 않는다.
 */
public class DiscordWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookClient.class);
    static final int MAX_RETRIES = 3;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final long MAX_WAIT_MILLIS = 30_000;

    /** 테스트에서 실제로 쉬지 않게 바꿔 끼운다. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;

    public DiscordWebhookClient(HttpClient http, ObjectMapper objectMapper, Sleeper sleeper) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    public boolean post(String webhookUrl, String jsonBody, String tag) {
        URI uri;
        try {
            uri = URI.create(webhookUrl.trim());
        } catch (IllegalArgumentException invalid) {
            log.warn("discord.failed tag={} reason=invalid-url", tag);
            return false;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        boolean retriedServerError = false;
        int rateLimited = 0;
        while (true) {
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.warn("discord.failed tag={} reason=interrupted", tag);
                return false;
            } catch (Exception network) {
                if (!retriedServerError) {
                    retriedServerError = true;
                    if (!pause(1_000)) return false;
                    continue;
                }
                log.warn("discord.failed tag={} reason={}", tag, network.getClass().getSimpleName());
                return false;
            }
            int status = response.statusCode();
            if (status / 100 == 2) {
                log.info("discord.sent tag={} status={}", tag, status);
                return true;
            }
            if (status == 429 && rateLimited < MAX_RETRIES) {
                rateLimited++;
                if (!pause(retryAfterMillis(response))) return false;
                continue;
            }
            if (status >= 500 && !retriedServerError) {
                retriedServerError = true;
                if (!pause(1_000)) return false;
                continue;
            }
            log.warn("discord.failed tag={} status={}", tag, status);
            return false;
        }
    }

    long retryAfterMillis(HttpResponse<String> response) {
        double seconds = 1.0;
        try {
            JsonNode body = objectMapper.readTree(response.body() == null ? "" : response.body());
            if (body != null && body.has("retry_after")) {
                seconds = body.get("retry_after").asDouble(1.0);
            } else {
                seconds = response.headers().firstValue("retry-after").map(Double::parseDouble).orElse(1.0);
            }
        } catch (Exception unreadable) {
            // 기본 1초.
        }
        long millis = (long) Math.ceil(Math.max(0.1, seconds) * 1000);
        return Math.min(millis, MAX_WAIT_MILLIS);
    }

    private boolean pause(long millis) {
        try {
            sleeper.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
