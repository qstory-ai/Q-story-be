package com.qstory.backend.org.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.UuidGenerator;

/**
 * A single-use, expiring invite for one ClassGroup - same hashed-secret posture as
 * VoiceResearchConsent's deletionTokenHash: the raw token is returned once at creation and
 * never stored, only its SHA-256 hash (see DigestUtil.sha256Hex).
 */
@Entity
@Table(name = "class_invite")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassInvite {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_group_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ClassGroup classGroup;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Null until redeemed; a used invite is never usable again. */
    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
