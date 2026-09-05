package com.qstory.backend.identity.repository;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.OAuthProvider;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    /** 탈퇴(소프트 삭제)된 계정을 제외하고 조회한다 - me()/updateProfile()/changePassword()/deleteAccount()가 사용. */
    Optional<AppUser> findByIdAndDeletedAtIsNull(UUID id);

    Optional<AppUser> findByOauthProviderAndOauthSubject(OAuthProvider oauthProvider, String oauthSubject);

    /** 반에 속한 특정 역할의 사용자를 최근 가입 순으로 조회 - 기관 관리자용 반 상세에서 사용. */
    List<AppUser> findByClassGroup_IdAndRoleAndDeletedAtIsNullOrderByCreatedAtDesc(UUID classGroupId, Role role);

    /** 기관에 속한 특정 역할의 사용자 수 - 이용 현황 집계용. */
    long countByOrganization_IdAndRoleAndDeletedAtIsNull(UUID organizationId, Role role);

    long countByClassGroup_IdAndRoleAndDeletedAtIsNull(UUID classGroupId, Role role);

    /**
     * 기관에 속한 특정 역할의 첫 사용자 - DIRECTOR는 조직당 하나뿐이라는 불변식(Organization
     * 클래스 헤더 참고)을 활용해 owning director를 찾을 때 쓴다. 데이터에 예상치 못한 중복이
     * 있어도 예외 대신 첫 하나를 반환하도록 findFirst를 쓴다.
     */
    Optional<AppUser> findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(UUID organizationId, Role role);

    /**
     * AuthService.createAccount()/loginOrSignupWithOAuth(), ClassService.create()/join(),
     * TutorStudentService.newParent()가 각자 따로 갖고 있던 "saveAndFlush 하고
     * DataIntegrityViolationException이면 LOGIN_ID_ALREADY_REGISTERED로 변환" 패턴을 하나로
     * 모았다. saveAndFlush를 쓰는 이유(save가 아니라)는 AuthService.createAccount()의 원래
     * 주석 참고 - 클라이언트가 미리 만든 @UuidGenerator id를 쓰면 INSERT가 커밋 시점까지
     * 지연될 수 있어, 여기서 강제로 flush해야 제약 조건 위반을 동기적으로 catch할 수 있다.
     */
    default AppUser saveOrThrowDuplicate(AppUser user, String safeDetail) {
        try {
            return saveAndFlush(user);
        } catch (DataIntegrityViolationException collision) {
            throw ApiException.contractError(ErrorCode.LOGIN_ID_ALREADY_REGISTERED, safeDetail);
        }
    }
}
