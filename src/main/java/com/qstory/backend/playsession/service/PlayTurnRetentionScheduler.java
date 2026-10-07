package com.qstory.backend.playsession.service;

import com.qstory.backend.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 리포트용 대화 원문(play_turn)의 보관 기간 만료 삭제. 기간은 대화 원장과 같은 qstory.conversation-record.retention-days
 * (기본 365일, 0 이하면 지우지 않음). 지워진 회차의 리포트는 요약(outcomes)으로만 보인다.
 */
@Component
public class PlayTurnRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(PlayTurnRetentionScheduler.class);

    private final PlaySessionService service;
    private final AppProperties config;

    public PlayTurnRetentionScheduler(PlaySessionService service, AppProperties config) {
        this.service = service;
        this.config = config;
    }

    @Scheduled(cron = "0 25 19 * * *", zone = "UTC")
    public void deleteExpiredTurns() {
        int retentionDays = config.conversationRecord() == null ? 365 : config.conversationRecord().retentionDays();
        if (retentionDays <= 0) {
            return;
        }
        try {
            int deleted = service.deleteSessionsUpdatedBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
            if (deleted > 0) {
                log.info("play-turn-retention.deleted count={} retentionDays={}", deleted, retentionDays);
            }
        } catch (RuntimeException error) {
            log.error("play-turn-retention.failed reason={}", error.toString(), error);
        }
    }
}
