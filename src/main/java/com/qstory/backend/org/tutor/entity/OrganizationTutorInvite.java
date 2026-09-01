package com.qstory.backend.org.tutor.entity;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.org.entity.Organization;
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
 * 기관 관리자가 선생님을 초대할 때 발급하는 1회용, 만료 가능한 토큰. ClassInvite/TutorInvite와
 * 같은 규약: 원본 token은 발급 시 한 번만 반환되고 저장은 sha-256 해시로만, 함께 발급되는
 * short_code(8자)는 손으로 옮길 수 있어 URL 없이도 코드 입력만으로 수락이 가능하다.
 */
@Entity
@Table(name = "organization_tutor_invite")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationTutorInvite {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Organization organization;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "short_code", nullable = false, unique = true, length = 16)
    private String shortCode;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 사용되기 전까지는 null. 사용 후 다시 사용할 수 없다. */
    @Column(name = "used_at")
    private Instant usedAt;

    /** 실제로 수락한 선생님 - 감사(audit) 목적으로 남겨 두고, 계정 삭제 시엔 null로 정리된다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "used_by_tutor_id")
    private AppUser usedByTutor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
