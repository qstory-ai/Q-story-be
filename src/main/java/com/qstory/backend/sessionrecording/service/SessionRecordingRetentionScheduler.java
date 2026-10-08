package com.qstory.backend.sessionrecording.service;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 받은 지 1년이 지난 화면 녹화 조각을 매일 지운다 - 베타 세션(BetaSessionRetentionScheduler)과 같은 보관 기간. */
@Component
public class SessionRecordingRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionRecordingRetentionScheduler.class);
    static final Duration RETENTION = Duration.ofDays(365);

    private final SessionRecordingService service;

    public SessionRecordingRetentionScheduler(SessionRecordingService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 40 18 * * *", zone = "UTC")
    public void deleteExpiredChunks() {
        try {
            int deleted = service.deleteReceivedBefore(Instant.now().minus(RETENTION));
            if (deleted > 0) {
                log.info("session-recording-retention.deleted count={}", deleted);
            }
        } catch (RuntimeException error) {
            log.error("session-recording-retention.failed reason={}", error.toString(), error);
        }
    }
}
