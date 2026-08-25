package com.qstory.backend.voiceresearch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Supabase의 pg_cron 작업 "qstory-delete-expired-voice-research"를 대체한다(기존에는 매일 UTC 18:45에 엣지 함수로 HTTP 호출을 거쳐 실행되었다). */
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
