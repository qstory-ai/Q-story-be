package com.qstory.backend.interaction.service;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 받은 지 90일이 지난 화면 상호작용을 매일 지운다 - 베타 세션(BetaSessionRetentionScheduler)과 같은 보관 기간. */
@Component
public class InteractionRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(InteractionRetentionScheduler.class);
    static final Duration RETENTION = Duration.ofDays(90);

    private final InteractionService service;

    public InteractionRetentionScheduler(InteractionService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 35 18 * * *", zone = "UTC")
    public void deleteExpiredEvents() {
        try {
            int deleted = service.deleteReceivedBefore(Instant.now().minus(RETENTION));
            if (deleted > 0) {
                log.info("interaction-retention.deleted count={}", deleted);
            }
        } catch (RuntimeException error) {
            log.error("interaction-retention.failed reason={}", error.toString(), error);
        }
    }
}
