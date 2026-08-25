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
 * 하나의 ClassGroup에 대한 1회용, 만료 가능한 초대 - VoiceResearchConsent의 deletionTokenHash와 동일한
 * 해시 시크릿 방식을 따른다: 원본 토큰은 생성 시 한 번만 반환되며 절대 저장되지 않고, 오직 그 SHA-256
 * 해시값만 저장된다(DigestUtil.sha256Hex 참고).
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

    /** 사용되기 전까지는 null이며, 이미 사용된 초대는 다시 사용할 수 없다. */
    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
