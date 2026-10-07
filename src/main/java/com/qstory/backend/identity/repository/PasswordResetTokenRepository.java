package com.qstory.backend.identity.repository;

import com.qstory.backend.identity.entity.PasswordResetToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** 회원 탈퇴(AccountErasureService) - 계정 행은 익명화로 남으므로 FK cascade가 돌지 않아 직접 지운다. */
    @Modifying
    @Query("delete from PasswordResetToken x where x.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
