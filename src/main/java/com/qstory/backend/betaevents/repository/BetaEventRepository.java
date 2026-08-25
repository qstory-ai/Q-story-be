package com.qstory.backend.betaevents.repository;

import com.qstory.backend.betaevents.entity.StoryEvent;
import com.qstory.backend.betaevents.entity.StorySession;
import com.qstory.backend.betaevents.repository.StoryEventRepository;
import com.qstory.backend.betaevents.repository.StorySessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** BetaEventService를 위해 StorySessionRepository/StoryEventRepository를 감싸는 래퍼. */
@Component
public class BetaEventRepository {

    private final StorySessionRepository sessionRepository;
    private final StoryEventRepository eventRepository;

    public BetaEventRepository(StorySessionRepository sessionRepository, StoryEventRepository eventRepository) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
    }

    public boolean eventExists(UUID eventId) {
        return eventRepository.existsById(eventId);
    }

    public StorySession findSession(UUID sessionId) {
        return sessionRepository.findById(sessionId).orElse(null);
    }

    public void insertSessionIfAbsent(
            UUID sessionId, String storyId, String entrySource, String trafficType, Instant now) {
        sessionRepository.insertIfAbsent(sessionId, storyId, entrySource, trafficType, now, now);
    }

    public StorySession saveSession(StorySession session) {
        return sessionRepository.save(session);
    }

    public long countRecentEvents(UUID sessionId, Instant since) {
        return eventRepository.countBySession_IdAndReceivedAtAfter(sessionId, since);
    }

    public StoryEvent saveEvent(StoryEvent event) {
        return eventRepository.save(event);
    }
}
