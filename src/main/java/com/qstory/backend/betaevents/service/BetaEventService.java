package com.qstory.backend.betaevents.service;
import com.qstory.backend.betaevents.repository.BetaEventRepository;
import com.qstory.backend.betaevents.util.BetaEventValidator;

import com.qstory.backend.common.enums.EntrySource;
import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.enums.EventSource;
import com.qstory.backend.common.enums.TrafficType;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.betaevents.entity.StoryEvent;
import com.qstory.backend.betaevents.entity.StorySession;
import com.qstory.backend.shadow.service.ShadowIntentCollectionService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 베타 이벤트의 세션 upsert와 이벤트 insert를 하나의 트랜잭션 안에서 처리한다. */
@Service
public class BetaEventService {

    private static final String STORY_ID = "hansel-gretel";
    private static final int RATE_LIMIT_MAX_EVENTS = 120;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);

    private final BetaEventRepository repository;
    private final ShadowIntentCollectionService shadowIntentCollectionService;

    public BetaEventService(BetaEventRepository repository, ShadowIntentCollectionService shadowIntentCollectionService) {
        this.repository = repository;
        this.shadowIntentCollectionService = shadowIntentCollectionService;
    }

    @Transactional
    public void record(BetaEventValidator.ParsedEvent event) {
        record(event, null);
    }

    /** userId: 로그인한 채 보낸 이벤트면 그 계정 - 세션에 남겨 참여자의 흐름을 잇는다(Q-40). 없으면 익명 그대로. */
    @Transactional
    public void record(BetaEventValidator.ParsedEvent event, UUID userId) {
        if (repository.eventExists(event.eventId())) {
            return;
        }

        Instant now = Instant.now();
        EntrySource entrySource = switch (event.source()) {
            case LANDING -> EntrySource.LANDING;
            case PLAYER -> EntrySource.PLAYER;
            // 앱 화면(가입·반 연결·리포트)에서 시작한 세션은 랜딩·플레이어를 거치지 않은 직접 진입이다.
            case APP -> EntrySource.DIRECT;
        };
        repository.insertSessionIfAbsent(event.sessionId(), STORY_ID, entrySource.name(), TrafficType.UNKNOWN, now);
        StorySession session = repository.findSession(event.sessionId());
        applyEventToSession(session, event, now);
        if (userId != null) {
            session.setUserId(userId);
        }
        repository.saveSession(session);

        long recentEvents = repository.countRecentEvents(event.sessionId(), now.minus(RATE_LIMIT_WINDOW));
        if (recentEvents >= RATE_LIMIT_MAX_EVENTS) {
            throw ApiException.contractError(ErrorCode.RATE_LIMITED, "요청이 너무 잦아요.", 429);
        }

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
