package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.StoryEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryEventRepository extends JpaRepository<StoryEvent, UUID> {

    long countBySession_IdAndReceivedAtAfter(UUID sessionId, Instant since);
}
