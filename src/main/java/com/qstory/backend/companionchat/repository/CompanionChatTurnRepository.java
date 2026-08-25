package com.qstory.backend.companionchat.repository;

import com.qstory.backend.companionchat.entity.CompanionChatTurn;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanionChatTurnRepository extends JpaRepository<CompanionChatTurn, UUID> {

    long countByConversationIdAndOccurredAtAfter(UUID conversationId, Instant after);

    @Modifying
    @Query("delete from CompanionChatTurn t where t.occurredAt < :cutoff")
    int deleteAllWithOccurredAtBefore(@Param("cutoff") Instant cutoff);
}
