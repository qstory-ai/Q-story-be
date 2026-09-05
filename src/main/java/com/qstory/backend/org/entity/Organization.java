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

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
