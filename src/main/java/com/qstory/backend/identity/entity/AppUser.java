package com.qstory.backend.identity.entity;

import com.qstory.backend.identity.Role;
import com.qstory.backend.org.SubscriptionStatus;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
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
import org.hibernate.annotations.UuidGenerator;

/**
 * 세 역할(DIRECTOR/CLASS_ACCOUNT/PARENT)을 모두 포괄하는 하나의 로그인 계정 - 역할별 테이블이나
 * @Inheritance 대신 role 구분자를 둔 단일 테이블로 구성했는데, 로그인은 언제나 역할과 무관한
 * 단일 loginId 조회이고 세 역할의 필드가 상당 부분 겹치기 때문이다(auth plan 문서 참고).
 * organization/classGroup은 역할에 따라 null일 수 있다: DIRECTOR는 classGroup이 없고(조직을
 * 만들기 전까지는 organization도 없다); CLASS_ACCOUNT/PARENT는 항상 둘 다 가진다.
 *
 * <p>Organization/ClassGroup으로부터 의도적으로 cascade 삭제되지 않는다 - 조직/학급을 삭제해도
 * 누군가의 로그인이 조용히 함께 사라지는 일은 절대 없어야 한다(현재는 둘 다 삭제 엔드포인트가
 * 아직 없으므로 실질적 의미는 없다).
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    @UuidGenerator
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** DIRECTOR/PARENT에게는 이메일; CLASS_ACCOUNT에게는 원장이 선택한 핸들 - ClassGroup.joinCode와는 별개다. */
    @Column(nullable = false, unique = true)
    private String loginId;

    /** BCrypt 해시. 나중에 추가될 OAuth 전용 계정(예: 카카오 로그인)에 대비해 nullable로 둔다. */
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_group_id")
    private ClassGroup classGroup;

    /**
     * 학부모 개인 구독 상태 - 유치원과 무관하게 본인이 결제해 전체 서재를 여는 경로다.
     * DIRECTOR/CLASS_ACCOUNT 행에서는 이 값이 의미가 없어 항상 NONE으로 남는다(구매 주체가
     * 아니므로). {@link com.qstory.backend.entitlement.service.EntitlementService}는 이 값과
     * organization.subscriptionStatus를 OR로 합쳐 판단한다 - 어느 한쪽만 있어도 접근을 잃지
     * 않아야 하기 때문이다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.NONE;

    private Instant subscriptionUpdatedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
