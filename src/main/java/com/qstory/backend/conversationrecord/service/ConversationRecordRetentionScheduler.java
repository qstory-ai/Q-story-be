package com.qstory.backend.conversationrecord.service;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.conversationrecord.repository.ConversationRecordRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화 원장의 보존 기간 만료 삭제. 기간은 qstory.conversation-record.retention-days
 * (환경변수 QSTORY_CONVERSATION_RECORD_RETENTION_DAYS, 기본 365). 0 이하로 두면 삭제하지 않는다.
 *
 * <p>CompanionChatRetentionScheduler(90일 고정)와 시각을 조금 비껴 둔다 - 같은 순간에 두 삭제가
 * 돌 이유가 없다.
 */
@Component
public class ConversationRecordRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConversationRecordRetentionScheduler.class);

    private final ConversationRecordRepository repository;
    private final AppProperties config;

    public ConversationRecordRetentionScheduler(ConversationRecordRepository repository, AppProperties config) {
        this.repository = repository;
        this.config = config;
    }

    @Scheduled(cron = "0 15 19 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredRecords() {
        int retentionDays = config.conversationRecord() == null ? 365 : config.conversationRecord().retentionDays();
        if (retentionDays <= 0) {
            return;
        }
        try {
            int deleted = repository.deleteAllWithRecordedAtBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
            if (deleted > 0) {
                log.info("conversation-record-retention.deleted count={} retentionDays={}", deleted, retentionDays);
            }
        } catch (Exception error) {
            log.error("conversation-record-retention.failed reason={}", error.toString(), error);
            throw error;
        }
    }
}
