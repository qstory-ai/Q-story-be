package com.qstory.backend.betaevents.service;
import com.qstory.backend.betaevents.repository.BetaEventRepository;
import com.qstory.backend.betaevents.util.BetaEventValidator;

import com.qstory.backend.common.enums.EntrySource;
import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.enums.EventSource;
import com.qstory.backend.common.enums.TrafficType;
import com.qstory.backend.betaevents.entity.StoryEvent;
import com.qstory.backend.betaevents.entity.StorySession;
import com.qstory.backend.shadow.service.ShadowIntentCollectionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 베타 이벤트의 세션 upsert와 이벤트 insert를 하나의 트랜잭션 안에서 처리한다. 요청 스레드가 아니라
 * BetaEventIngestQueue의 저장 스레드가 묶음으로 부른다. 세션당 보내기 한도(429)는 요청에서 BetaEventRateLimiter가 본다.
 */
@Service
public class BetaEventService {

    private static final Logger log = LoggerFactory.getLogger(BetaEventService.class);
    private static final String STORY_ID = "hansel-gretel";

    private final BetaEventRepository repository;
    private final ShadowIntentCollectionService shadowIntentCollectionService;
    private final TransactionOperations transactions;

    public BetaEventService(
            BetaEventRepository repository, ShadowIntentCollectionService shadowIntentCollectionService,
            TransactionOperations transactions) {
        this.repository = repository;
        this.shadowIntentCollectionService = shadowIntentCollectionService;
        this.transactions = transactions;
    }

    /**
     * 묶음 전체를 한 트랜잭션으로 저장한다(왕복·커밋을 줄인다). 한 건 때문에 묶음이 실패하면 그 묶음은 한 건씩 따로 저장해서,
     * 잘못된 한 건만 버리고 나머지는 남긴다.
     */
    public void storeBatch(List<BetaEventIngestQueue.Pending> batch) {
        try {
            transactions.executeWithoutResult(status -> batch.forEach(this::store));
        } catch (RuntimeException batchFailure) {
            for (BetaEventIngestQueue.Pending pending : batch) {
                try {
                    transactions.executeWithoutResult(status -> store(pending));
                } catch (RuntimeException failure) {
                    log.warn("beta-events.store-failed event={} reason={}",
                            pending.event().eventName(), failure.getClass().getSimpleName());
                }
            }
        }
    }

    private void store(BetaEventIngestQueue.Pending pending) {
        record(pending.event(), pending.userId(), pending.receivedAt());
    }

    @Transactional
    public void record(BetaEventValidator.ParsedEvent event, UUID userId) {
        record(event, userId, Instant.now());
    }

    /**
     * userId: 로그인한 채 보낸 이벤트면 그 계정 - 세션에 남겨 참여자의 흐름을 잇는다(Q-40). 없으면 익명 그대로. 이미 다른
     * 계정이 이어진 세션은 바꾸지 않는다. now: 요청을 받은 시각(received_at, last_seen_at).
     */
    @Transactional
    public void record(BetaEventValidator.ParsedEvent event, UUID userId, Instant now) {
        if (repository.eventExists(event.eventId())) {
            return;
        }

        EntrySource entrySource = switch (event.source()) {
            case LANDING -> EntrySource.LANDING;
            case PLAYER -> EntrySource.PLAYER;
            // 앱 화면(가입·반 연결·리포트)에서 시작한 세션은 랜딩·플레이어를 거치지 않은 직접 진입이다.
            case APP -> EntrySource.DIRECT;
        };
        repository.insertSessionIfAbsent(event.sessionId(), STORY_ID, entrySource.name(), TrafficType.UNKNOWN, now);
        StorySession session = repository.findSession(event.sessionId());
        applyEventToSession(session, event, now);
        // 처음 이어진 계정만 남긴다(보안 점검 L2) - 남의 베타 세션 id로 이벤트를 보내 그 세션(과 이어진 녹화 동의·탈퇴 정리)을
        // 자기 계정으로 바꿔 달지 못하게 한다.
        if (userId != null && session.getUserId() == null) {
            session.setUserId(userId);
        }
        repository.saveSession(session);

        StoryEvent savedEvent = repository.saveEvent(StoryEvent.builder()
                .id(event.eventId())
                .session(session)
                .eventName(event.eventName())
                .source(event.source())
                .occurredAt(event.occurredAt())
                .receivedAt(now)
                .metadata(event.metadata())
                .build());

        if (event.eventName() == EventName.QUESTION_RESULT) {
            shadowIntentCollectionService.collectFromQuestionResultEvent(savedEvent, session);
        }
    }

    private void applyEventToSession(StorySession session, BetaEventValidator.ParsedEvent event, Instant now) {
        session.setLastSeenAt(now);

        Object trafficType = event.metadata().get("traffic_type");
        if (trafficType instanceof String value && !"unknown".equals(value)) {
            String upper = value.toUpperCase();
            if (TrafficType.VALUES.contains(upper)) {
                session.setTrafficType(upper);
            }
            // 인식할 수 없는 traffic_type 값은 이벤트 전체를 거부하지 않고 그냥 버린다
        }

        // 앱으로 바로 들어온 방문(APP_ENTRY)도 유입 경로(utm)를 남긴다(Q-40).
        if (event.source() == EventSource.LANDING || event.eventName() == EventName.APP_ENTRY) {
            setIfPresent(event, "landing_release", session::setLandingRelease);
            setIfPresent(event, "utm_source", session::setUtmSource);
            setIfPresent(event, "utm_medium", session::setUtmMedium);
            setIfPresent(event, "utm_campaign", session::setUtmCampaign);
            setIfPresent(event, "utm_content", session::setUtmContent);
        }

        switch (event.eventName()) {
            case LANDING_VIEW -> {
                if (session.getFirstLandingAt() == null) {
                    session.setFirstLandingAt(now);
                }
            }
            case STORY_STARTED -> {
                if (session.getPlayerStartedAt() == null) {
                    session.setPlayerStartedAt(now);
                }
            }
            case STORY_COMPLETED -> session.setCompletedAt(now);
            case SURVEY_OPENED -> session.setSurveyOpenedAt(now);
            default -> {
                // 이 이벤트에 대해서는 세션 수준의 타임스탬프가 없음
            }
        }
    }

    private void setIfPresent(BetaEventValidator.ParsedEvent event, String key, java.util.function.Consumer<String> setter) {
        Object value = event.metadata().get(key);
        if (value instanceof String stringValue) {
            setter.accept(stringValue);
        }
    }
}
