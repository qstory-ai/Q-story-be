package com.qstory.backend.push.repository;

import com.qstory.backend.push.entity.PushDeviceToken;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PushDeviceTokenRepository extends JpaRepository<PushDeviceToken, UUID> {

    /**
     * 토큰을 호출자 것으로 등록한다. 이미 있는 토큰(같은 기기)이면 다른 계정 것이었어도 호출자로 옮기고, 비활성화를
     * 풀고, last_seen_at을 갱신한다. token unique에 기대 한 문장으로 처리해 같은 기기의 동시 등록도 충돌하지 않는다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            insert into push_device_token (id, user_id, token, platform, created_at, last_seen_at, disabled_at)
            values (:id, :userId, :token, :platform, :now, :now, null)
            on conflict (token) do update
               set user_id = excluded.user_id,
                   platform = excluded.platform,
                   last_seen_at = excluded.last_seen_at,
                   disabled_at = null
            """, nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("userId") UUID userId, @Param("token") String token,
            @Param("platform") String platform, @Param("now") Instant now);

    /** 호출자의 토큰만 지운다 - 다른 계정으로 옮겨 간 토큰이면 아무것도 안 한다. */
    @Modifying
    @Transactional
    @Query("delete from PushDeviceToken t where t.userId = :userId and t.token = :token")
    int deleteByUserIdAndToken(@Param("userId") UUID userId, @Param("token") String token);

    @Query("select t.token from PushDeviceToken t where t.userId = :userId and t.disabledAt is null")
    List<String> findActiveTokens(@Param("userId") UUID userId);

    /** FCM이 UNREGISTERED 등으로 답한 토큰. 앱이 같은 토큰을 다시 올리면 upsert가 풀어 준다. */
    @Modifying
    @Transactional
    @Query("update PushDeviceToken t set t.disabledAt = :now where t.token = :token and t.disabledAt is null")
    int disable(@Param("token") String token, @Param("now") Instant now);

    /** 회원 탈퇴(AccountErasureService) - 계정 행은 익명화로 남으므로 FK cascade가 돌지 않아 직접 지운다. */
    @Modifying
    @Query("delete from PushDeviceToken t where t.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
