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

/** A pseudonymous, cookie-free funnel session. The id is client-generated (idempotent upsert), never DB-generated. */
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

    /** One of TrafficType.VALUES - plain string, see that class's doc for why. */
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
