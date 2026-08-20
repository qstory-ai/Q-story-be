package com.qstory.backend.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.enums.DraftReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** The generated (never auto-published) illustration + narration + branch script for one candidate. */
@Entity
@Table(name = "shadow_family_drafts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowFamilyDraft {

    @Id
    @UuidGenerator
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ShadowIntentCandidate candidate;

    @Column(nullable = false)
    @Builder.Default
    private int draftSchemaVersion = 1;

    @Column(nullable = false)
    private String promptVersion;

    @Column(nullable = false)
    private String llmModel;

    @Column(nullable = false)
    private String imageModel;

    @Column(nullable = false)
    private String ttsModel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode draftPayload;

    @Column(nullable = false, unique = true)
    private String imageObjectName;

    @Column(nullable = false, unique = true)
    private String audioObjectName;

    @Column(nullable = false)
    private String imageMimeType;

    @Column(nullable = false)
    @Builder.Default
    private String audioMimeType = "audio/wav";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DraftReviewStatus reviewStatus = DraftReviewStatus.PENDING_REVIEW;

    @Column(nullable = false, updatable = false)
    private Instant generatedAt;

    private Instant reviewedAt;

    @Column(length = 1000)
    private String reviewNote;
}
