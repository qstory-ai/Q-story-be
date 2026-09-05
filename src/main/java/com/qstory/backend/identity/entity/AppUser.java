package com.qstory.backend.identity.entity;

import com.qstory.backend.identity.OAuthProvider;
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
 * 만들기 전까지는 organization도 없다); CLASS_ACCOUNT는 항상 둘 다 가진다. PARENT는 반 코드로
 * 가입했으면(ClassService.join) 둘 다 갖지만, 반 코드 없이 가입한 "독립" 학부모(AuthService.
 * signupParent)는 둘 다 null이다 - 이 경우 entitlement 판단은 이 계정 자신의 subscriptionStatus
 * 하나로만 이뤄진다(EntitlementService 참고).
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

    /**
     * 사용자가 직접 정하는 로그인 아이디 - CLASS_ACCOUNT는 예외로 시스템이 발급한 handle을 쓴다.
     * 예전에는 이메일 형식이 강제되어 email과 사실상 같은 값이었지만, 지금은 자유 형식이고
     * 실제 이메일은 아래 email 컬럼에 별도로 저장한다.
     */
    @Column(nullable = false, unique = true)
    private String loginId;

    /**
     * 연락용 이메일 주소 - loginId와 달리 로그인 식별자가 아니고 unique 제약도 없다(같은 이메일로
     * 여러 역할 계정을 만드는 것을 막지 않는다). DIRECTOR/PARENT/TUTOR는 가입 시 필수로 받고,
     * CLASS_ACCOUNT는 원장이 반을 만들 때 이메일을 받지 않으므로 null이다.
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
