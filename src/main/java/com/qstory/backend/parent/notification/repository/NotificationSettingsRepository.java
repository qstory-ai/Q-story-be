package com.qstory.backend.parent.notification.repository;

import com.qstory.backend.parent.notification.entity.NotificationSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {

    /** 회원 탈퇴(AccountErasureService) - 계정 행은 익명화로 남으므로 FK cascade가 돌지 않아 직접 지운다. */
    @Modifying
    @Query("delete from NotificationSettings x where x.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
