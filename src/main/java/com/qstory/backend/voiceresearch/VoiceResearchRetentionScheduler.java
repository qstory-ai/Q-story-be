package com.qstory.backend.voiceresearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replaces the Supabase pg_cron job "qstory-delete-expired-voice-research" (was: daily at 18:45 UTC, via an HTTP hop to the edge function). */
@Component
public class VoiceResearchRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(VoiceResearchRetentionScheduler.class);

    private final VoiceResearchService service;

    public VoiceResearchRetentionScheduler(VoiceResearchService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 45 18 * * *", zone = "UTC")
    public void deleteExpiredConsents() {
        int deleted = service.cleanupExpired();
        if (deleted > 0) {
            log.info("voice-research-retention.deleted count={}", deleted);
        }
    }
}
