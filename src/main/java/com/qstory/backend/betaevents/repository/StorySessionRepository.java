package com.qstory.backend.betaevents.repository;

import com.qstory.backend.betaevents.entity.StorySession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StorySessionRepository extends JpaRepository<StorySession, UUID> {

    // BetaEventService.record()가 "없으면 만들고 있으면 그대로 둔다"를 별도 SELECT 후 INSERT로 하면,
    // 같은 신규 세션에 대해 거의 동시에 도착하는 두 이벤트(예: story_started/scene_reached)가 둘 다
    // "세션 없음"을 보고 각자 INSERT를 시도해 story_sessions_pkey 유니크 제약 위반으로 한쪽이 500을
    // 받는다. ON CONFLICT DO NOTHING으로 원자적 upsert를 만들어 이 경쟁을 원천적으로 없앤다 - 이후
    // BetaEventService는 findSession()으로 (지금 막 만들어졌든 이미 있었든) 반드시 존재하는 행을
    // 다시 읽어와 필드를 갱신하므로, 그 갱신은 항상 UPDATE가 되어 INSERT 경쟁이 다시 생기지 않는다.
    @Modifying
    @Query(
            value = """
                    insert into story_sessions (id, story_id, entry_source, traffic_type, last_seen_at, created_at)
                    values (:id, :storyId, :entrySource, :trafficType, :lastSeenAt, :createdAt)
                    on conflict (id) do nothing
                    """,
            nativeQuery = true)
    void insertIfAbsent(
            @Param("id") UUID id,
            @Param("storyId") String storyId,
            @Param("entrySource") String entrySource,
            @Param("trafficType") String trafficType,
            @Param("lastSeenAt") Instant lastSeenAt,
            @Param("createdAt") Instant createdAt);

    @Modifying
    @Query("delete from StorySession s where s.lastSeenAt < :cutoff")
    int deleteAllWithLastSeenBefore(@Param("cutoff") Instant cutoff);

    List<StorySession> findTop200ByLastSeenAtBefore(Instant cutoff);
}
