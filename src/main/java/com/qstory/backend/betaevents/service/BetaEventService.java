package com.qstory.backend.betaevents.service;
import com.qstory.backend.betaevents.repository.BetaEventRepository;
import com.qstory.backend.betaevents.util.BetaEventValidator;

import com.qstory.backend.common.enums.EntrySource;
import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.enums.EventSource;
import com.qstory.backend.common.enums.TrafficType;
import com.qstory.backend.common.error.EdgeErrorCode;
import com.qstory.backend.common.error.EdgeException;
import com.qstory.backend.betaevents.entity.StoryEvent;
import com.qstory.backend.betaevents.entity.StorySession;
import com.qstory.backend.shadow.service.ShadowIntentCollectionService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Java port of beta-events/index.ts's session upsert + event insert. Unlike the edge function
 * (two PostgREST round-trips through Supabase), this backend owns the database directly, so the
 * session upsert and event insert happen in one local transaction instead.
 */
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
        if (repository.eventExists(event.eventId())) {
            return;
        }

        StorySession session = repository.findSession(event.sessionId());
        Instant now = Instant.now();
        if (session == null) {
            session = StorySession.builder()
                    .id(event.sessionId())
                    .storyId(STORY_ID)
                    .entrySource(event.source() == EventSource.LANDING ? EntrySource.LANDING : EntrySource.PLAYER)
                    .trafficType(TrafficType.UNKNOWN)
                    .lastSeenAt(now)
                    .createdAt(now)
                    .build();
        }
        applyEventToSession(session, event, now);
        repository.saveSession(session);

        long recentEvents = repository.countRecentEvents(event.sessionId(), now.minus(RATE_LIMIT_WINDOW));
        if (recentEvents >= RATE_LIMIT_MAX_EVENTS) {
            throw new EdgeException(EdgeErrorCode.RATE_LIMITED);
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
            // unrecognized traffic_type values are dropped rather than rejecting the whole event
        }

        if (event.source() == EventSource.LANDING) {
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
                // no session-level timestamp for this event
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
