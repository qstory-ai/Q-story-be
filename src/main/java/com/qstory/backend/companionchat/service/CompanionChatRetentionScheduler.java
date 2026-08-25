package com.qstory.backend.companionchat.service;

import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * BetaSessionRetentionScheduler와는 독립적임 - companion_chat_turn은 story_sessions에 FK로
 * 묶여 있지 않으므로(그 테이블은 단일 베타 스토리의 분석 의미론에 하드코딩되어 있다),
 * 보존 기간(retention window)은 동일하더라도 자체적인 정리(sweep) 로직이 필요하다.
 */
@Component
public class CompanionChatRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CompanionChatRetentionScheduler.class);
    private static final Duration RETENTION = Duration.ofDays(90);

    private final CompanionChatTurnRepository repository;

    public CompanionChatRetentionScheduler(CompanionChatTurnRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 45 18 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredTurns() {
        int deleted = repository.deleteAllWithOccurredAtBefore(Instant.now().minus(RETENTION));
        if (deleted > 0) {
            log.info("companion-chat-retention.deleted count={}", deleted);
        }
    }
}
