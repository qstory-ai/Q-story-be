package com.qstory.backend.betaevents.repository;

import com.qstory.backend.betaevents.entity.StoryEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryEventRepository extends JpaRepository<StoryEvent, UUID> {

    long countBySession_IdAndReceivedAtAfter(UUID sessionId, Instant since);
}
