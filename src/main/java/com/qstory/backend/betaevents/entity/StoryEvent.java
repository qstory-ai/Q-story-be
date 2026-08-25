package com.qstory.backend.betaevents.entity;

import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.enums.EventSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/** 추가 전용(append-only) 퍼널/텔레메트리 이벤트 하나. id는 클라이언트에서 생성하므로 재시도된 업로드도 중복 제거된다. */
@Entity
@Table(
        name = "story_events",
        indexes = {
            @Index(name = "story_events_session_received_idx", columnList = "session_id, received_at"),
            @Index(name = "story_events_name_received_idx", columnList = "event_name, received_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private StorySession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventName eventName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventSource source;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
