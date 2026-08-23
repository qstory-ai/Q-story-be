package com.qstory.backend.voiceresearch.entity;

import com.qstory.backend.common.enums.CoverageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** A single opted-in question recording plus, once routed, its (redacted) coverage outcome. */
@Entity
@Table(name = "voice_research_samples")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoiceResearchSample {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private VoiceResearchConsent consent;

    @Column(nullable = false, unique = true)
    private String storageObjectName;

    @Column(nullable = false)
    private String storyId;

    @Column(nullable = false)
    private String sceneId;

    @Column(nullable = false)
    private String anchorId;

    @Column(nullable = false)
    private int questionRound;

    @Column(nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private int byteSize;

    @Column(nullable = false)
    private int durationMillis;

    @Column(nullable = false, length = 240)
    private String sttDraft;

    @Column(nullable = false, length = 240)
    private String confirmedTranscript;

    @Enumerated(EnumType.STRING)
    private CoverageStatus coverageStatus;

    private String routedFamilyId;

    @Column(length = 240)
    private String intentSummary;

    private Instant outcomeRecordedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
