package com.qstory.backend.identity.repository;

import com.qstory.backend.identity.OAuthProvider;
import com.qstory.backend.identity.entity.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    /** 탈퇴(소프트 삭제)된 계정을 제외하고 조회한다 - me()/updateProfile()/changePassword()/deleteAccount()가 사용. */
    Optional<AppUser> findByIdAndDeletedAtIsNull(UUID id);

    Optional<AppUser> findByOauthProviderAndOauthSubject(OAuthProvider oauthProvider, String oauthSubject);
}
