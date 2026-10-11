package com.qstory.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실시간 받아 적기(sttstream) 스위치와 상한. enabled=false면 표를 주지 않아 앱이 배치 받아 적기로 돌아간다 -
 * 문제가 생기면 STT_STREAM_ENABLED=false 하나로 끈다.
 */
@ConfigurationProperties(prefix = "qstory.stt-stream")
public record SttStreamProperties(
        boolean enabled,
        int maxConcurrent,
        long maxDurationMs,
        long ticketTtlMs,
        int sessionTicketLimit,
        int globalTicketsPerMinute) {

    public SttStreamProperties {
        maxConcurrent = maxConcurrent > 0 ? maxConcurrent : 10;
        maxDurationMs = maxDurationMs > 0 ? maxDurationMs : 35_000;
        ticketTtlMs = ticketTtlMs > 0 ? ticketTtlMs : 30_000;
        sessionTicketLimit = sessionTicketLimit > 0 ? sessionTicketLimit : 40;
        globalTicketsPerMinute = globalTicketsPerMinute > 0 ? globalTicketsPerMinute : 120;
    }
}
