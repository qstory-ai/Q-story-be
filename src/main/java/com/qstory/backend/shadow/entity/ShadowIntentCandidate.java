package com.qstory.backend.shadow.entity;

import com.qstory.backend.common.enums.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * 현재 스토리 콘텐츠가 정확히 다루지 못하는, 정규화되고 반복되는 아이의 의도(intent).
 * 아이에게 직접 노출되는 일은 없다 - {@code reviewStatus}는 READY_FOR_REVIEW까지만 자동으로 진행되며,
 * APPROVED/REJECTED/PROMOTED로의 전환은 항상 사람 검토자를 필요로 한다.
 */
@Entity
@Table(
        name = "shadow_intent_candidates",
        uniqueConstraints = @UniqueConstraint(columnNames = {"story_id", "anchor_id", "intent_signature"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowIntentCandidate {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String storyId;

    @Column(nullable = false)
    private String anchorId;

    @Column(name = "intent_signature", nullable = false)
    private String intentSignature;

    @Column(nullable = false, length = 240)
    private String representativeIntent;

    @Column(nullable = false)
    @Builder.Default
    private int occurrenceCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private int distinctSessionCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReviewStatus reviewStatus = ReviewStatus.COLLECTING;

    @Column(nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    private Instant reviewedAt;

    @Column(length = 1000)
    private String reviewNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
