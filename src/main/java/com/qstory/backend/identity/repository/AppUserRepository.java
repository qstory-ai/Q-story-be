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

    /**
     * 기관에 속한 특정 역할의 첫 사용자 - DIRECTOR는 조직당 하나뿐이라는 불변식(Organization
     * 클래스 헤더 참고)을 활용해 owning director를 찾을 때 쓴다. 데이터에 예상치 못한 중복이
     * 있어도 예외 대신 첫 하나를 반환하도록 findFirst를 쓴다.
     */
    Optional<AppUser> findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(UUID organizationId, Role role);

    /**
     * 새 계정을 저장하고 unique 제약 위반(주로 login_id 중복)을 LOGIN_ID_ALREADY_REGISTERED로 변환한다.
     * save가 아니라 saveAndFlush인 이유: INSERT가 커밋 시점까지 지연될 수 있어, 여기서 flush해야
     * 제약 위반을 동기적으로 catch할 수 있다.
     */
    default AppUser saveOrThrowDuplicate(AppUser user, String safeDetail) {
        try {
            return saveAndFlush(user);
        } catch (DataIntegrityViolationException collision) {
            throw ApiException.contractError(ErrorCode.LOGIN_ID_ALREADY_REGISTERED, safeDetail);
        }
    }
}
