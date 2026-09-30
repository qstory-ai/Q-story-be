package com.qstory.backend.betaevents.service;

import com.qstory.backend.betaevents.repository.StorySessionRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 마지막 활동 후 90일이 지난 베타 세션을 매일 삭제한다(이벤트는 FK cascade로 함께 삭제). */
@Component
public class BetaSessionRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(BetaSessionRetentionScheduler.class);
    private static final Duration RETENTION = Duration.ofDays(90);

    private final StorySessionRepository repository;

    public BetaSessionRetentionScheduler(StorySessionRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 30 18 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredSessions() {
        int deleted = repository.deleteAllWithLastSeenBefore(Instant.now().minus(RETENTION));
        if (deleted > 0) {
            log.info("beta-session-retention.deleted count={}", deleted);
        }
    }
}
