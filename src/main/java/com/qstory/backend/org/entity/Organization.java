package com.qstory.backend.org.entity;

import com.qstory.backend.org.SubscriptionStatus;
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
import org.hibernate.annotations.UuidGenerator;

/**
 * 유치원. 여기에는 소유 원장(owning-director)을 가리키는 FK가 없다 - 원장은 role=DIRECTOR이고
 * organization=this인 유일한 AppUser이며, 이는 중복되는 양방향 포인터 대신 OrganizationService.create()에서
 * 강제된다(호출자가 이미 하나를 소유하고 있으면 409를 반환).
 */
@Entity
@Table(name = "organization")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.NONE;

    private Instant subscriptionUpdatedAt;

    @Column(name = "subscription_expires_at")
    private Instant subscriptionExpiresAt;

    /** 마지막 결제로 이용권이 적용되는 학생 수. null이면 인원 제한 없이 결제된 예전 구독이다. */
    @Column(name = "subscription_seats")
    private Integer subscriptionSeats;

    /**
     * 기관이 "원아의 보호자 동의를 받아 이용한다(기관이 보호자에게 안내·동의를 받는다)"를 마지막으로 확인한 시각·문구 버전·확인한
     * 원장(A5, db/schema/082). 원장 계정의 동의 이력(user_consent, ORG_GUARDIAN_ACK)에도 매번 한 줄씩 쌓인다.
     */
    @Column(name = "guardian_consent_ack_at")
    private Instant guardianConsentAckAt;

    @Column(name = "guardian_consent_ack_version", length = 40)
    private String guardianConsentAckVersion;

    @Column(name = "guardian_consent_ack_by")
    private UUID guardianConsentAckBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
