package com.qstory.backend.betaevents;

import com.qstory.backend.persistence.repository.StorySessionRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Replaces the Supabase pg_cron job "qstory-delete-expired-beta-sessions" (was: daily at 18:30 UTC). */
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
