package com.qstory.backend.voiceresearch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 만료된 음성 연구 동의와 그 녹음(Storage 객체 포함)을 매일 삭제한다. */
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
