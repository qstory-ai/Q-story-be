package com.qstory.backend.persistence.entity;

import com.qstory.backend.common.enums.JobStatus;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/** A leased work item for generating a draft shadow family from a candidate that reached READY_FOR_REVIEW. */
@Entity
@Table(name = "shadow_generation_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowGenerationJob {

    @Id
    @UuidGenerator
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ShadowIntentCandidate candidate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private JobStatus status = JobStatus.QUEUED;

    @Column(nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    private String workerId;
    private Instant leaseExpiresAt;
    private String errorCode;

    @Column(nullable = false, updatable = false)
    private Instant queuedAt;

    private Instant startedAt;
    private Instant completedAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
