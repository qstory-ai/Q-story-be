package com.qstory.backend.betaevents.entity;

import com.qstory.backend.common.enums.EntrySource;
import com.qstory.backend.common.enums.TrafficType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 가명화(pseudonymous)되고 쿠키를 사용하지 않는 퍼널 세션. id는 클라이언트에서 생성되며(멱등성 있는 upsert), DB에서 생성되는 경우는 없다. */
@Entity
@Table(name = "story_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorySession {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String storyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntrySource entrySource;

    /** TrafficType.VALUES 중 하나 - 일반 문자열이며, 그 이유는 해당 클래스의 문서 참조. */
    @Column(nullable = false)
    @Builder.Default
    private String trafficType = TrafficType.UNKNOWN;

    private String landingRelease;
    private String utmSource;
    private String utmMedium;
    private String utmCampaign;
    private String utmContent;

    private Instant firstLandingAt;
    private Instant playerStartedAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    private Instant completedAt;
    private Instant surveyOpenedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
