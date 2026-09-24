package com.qstory.backend.conversationrecord.repository;

import com.qstory.backend.conversationrecord.entity.ConversationRecord;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * insert와 보존 만료 삭제만 쓴다. 조회 메서드를 여기에 추가하기 전에 그 조회가 어떤 권한 모델로
 * 누구에게 보이는지부터 정해야 한다 - 이 테이블은 아이의 발화 원문을 담는다.
 */
public interface ConversationRecordRepository extends JpaRepository<ConversationRecord, UUID> {

    @Modifying
    @Query("delete from ConversationRecord r where r.recordedAt < :cutoff")
    int deleteAllWithRecordedAtBefore(@Param("cutoff") Instant cutoff);
}
