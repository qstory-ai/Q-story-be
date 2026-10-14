package com.qstory.backend.partnership.service;

import com.qstory.backend.partnership.repository.PartnershipInquiryRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 받은 지 1년이 지난 협업 문의를 매일 지운다 - 처리방침의 선택 참여 양식 보관 기간(수집일로부터 1년)과 같게. */
@Component
public class PartnershipInquiryRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(PartnershipInquiryRetentionScheduler.class);
    static final Duration RETENTION = Duration.ofDays(365);

    private final PartnershipInquiryRepository repository;

    public PartnershipInquiryRetentionScheduler(PartnershipInquiryRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 45 18 * * *", zone = "UTC")
    public void deleteExpired() {
        try {
            int deleted = repository.deleteCreatedBefore(Instant.now().minus(RETENTION));
            if (deleted > 0) {
                log.info("partnership-inquiry-retention.deleted count={}", deleted);
            }
        } catch (RuntimeException error) {
            log.error("partnership-inquiry-retention.failed reason={}", error.toString(), error);
        }
    }
}
