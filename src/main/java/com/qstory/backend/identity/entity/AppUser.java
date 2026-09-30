package com.qstory.backend.identity.entity;

import com.qstory.backend.identity.OAuthProvider;
import com.qstory.backend.identity.Role;
import com.qstory.backend.org.SubscriptionStatus;
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
 * 모든 역할(DIRECTOR/PARENT/TUTOR/STAFF)을 포괄하는 하나의 로그인 계정 - 로그인은 언제나 역할과 무관한
 * 단일 loginId 조회이고 역할별 필드가 상당 부분 겹쳐서 role 구분자를 둔 단일 테이블로 구성했다.
 * organization은 DIRECTOR만 가진다(기관을 만들기 전까지는 DIRECTOR도 null). 학부모·선생님의 기관
 * 소속은 학생 명단(TutorStudent.classGroup)과 organization_tutor로 표현하며 이 계정에는 남기지 않는다.
 *
 * <p>Organization으로부터 의도적으로 cascade 삭제되지 않는다 - 기관을 삭제해도 누군가의 로그인이
 * 조용히 함께 사라지는 일은 없어야 한다.
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

    /**
     * 사용자가 직접 정하는 자유 형식 로그인 아이디. 실제 이메일은 아래 email 컬럼에 별도로 저장한다.
     */
    @Column(nullable = false, unique = true)
    private String loginId;

    /**
     * 연락용 이메일 주소 - loginId와 달리 로그인 식별자가 아니고 unique 제약도 없다(같은 이메일로
     * 여러 역할 계정을 만드는 것을 막지 않는다). 가입 시 필수로 받는다.
     */
    private String email;

    /** BCrypt 해시. OAuth 전용 계정(oauthProvider가 채워진 행)에서는 null이다. */
    private String passwordHash;

    /** 소셜 로그인으로 만들어진 계정에서만 채워진다 - 둘 다 null이면 비밀번호 계정이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider")
    private OAuthProvider oauthProvider;

    @Column(name = "oauth_subject")
    private String oauthSubject;

    @Column(nullable = false)
    private String displayName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    /**
     * 학부모 개인 구독 상태 - 유치원과 무관하게 본인이 결제해 전체 서재를 여는 경로다.
     * DIRECTOR 행에서는 이 값이 의미가 없어 항상 NONE으로 남는다(구매 주체가
     * 아니므로). {@link com.qstory.backend.entitlement.service.EntitlementService}는 이 값과
     * organization.subscriptionStatus를 OR로 합쳐 판단한다 - 어느 한쪽만 있어도 접근을 잃지
     * 않아야 하기 때문이다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.NONE;

    private Instant subscriptionUpdatedAt;

    @Column(name = "subscription_expires_at")
    private Instant subscriptionExpiresAt;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Column(name = "profile_image_object_name")
    private String profileImageObjectName;

    /** PARENT 역할에서만 의미가 있다 - 마이페이지 "내 정보 관리"에서 학부모가 직접 입력한다. */
    @Column(name = "child_name")
    private String childName;

    /**
     * 회원 탈퇴 시각 - null이 아니면 로그인/현재 사용자 조회 대상에서 제외된다(AppUserRepository.
     * findByIdAndDeletedAtIsNull 참고). 하드 삭제 대신 소프트 삭제로 처리하는 이유는 비밀번호
     * 재설정 토큰/튜터 학생/스토리 완료 기록 등 다른 테이블이 이 행을 FK로 참조하고 있어서다.
     */
    private Instant deletedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
