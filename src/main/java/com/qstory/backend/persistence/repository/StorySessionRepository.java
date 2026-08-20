package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.StorySession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StorySessionRepository extends JpaRepository<StorySession, UUID> {

    @Modifying
    @Query("delete from StorySession s where s.lastSeenAt < :cutoff")
    int deleteAllWithLastSeenBefore(@Param("cutoff") Instant cutoff);

    List<StorySession> findTop200ByLastSeenAtBefore(Instant cutoff);
}
