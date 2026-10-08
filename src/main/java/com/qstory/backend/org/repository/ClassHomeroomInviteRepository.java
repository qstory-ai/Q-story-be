package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.ClassHomeroomInvite;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClassHomeroomInviteRepository extends JpaRepository<ClassHomeroomInvite, UUID> {

    Optional<ClassHomeroomInvite> findByShortCode(String shortCode);

    /** 수락 - 같은 코드를 두 선생님이 동시에 수락하지 못하게 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ClassHomeroomInvite i where i.shortCode = :shortCode")
    Optional<ClassHomeroomInvite> lockByShortCode(@Param("shortCode") String shortCode);

    boolean existsByShortCode(String shortCode);

    /** 지금 쓸 수 있는(쓰지 않았고, 바뀌지 않았고, 만료되지 않은) 초대. */
    Optional<ClassHomeroomInvite>
            findFirstByClassGroup_IdAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                    UUID classGroupId, Instant now);

    /**
     * 새로 발급하기 전에 살아 있는 이전 초대(만료된 것 포함)를 "새 코드로 바뀜"으로 표시한다. 바로 실행되는 일괄 갱신이라
     * 같은 트랜잭션에서 새 초대를 넣어도 부분 유니크 인덱스(class_group_id where used_at/revoked_at is null)에 걸리지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update ClassHomeroomInvite i set i.revokedAt = :now "
            + "where i.classGroup.id = :classGroupId and i.usedAt is null and i.revokedAt is null")
    int revokeLiveByClassGroupId(@Param("classGroupId") UUID classGroupId, @Param("now") Instant now);

    /**
     * (from, to] 사이에 만료된, 쓰지 않은 초대 - 새 코드로 바뀐 뒤에 만료된 것은 뺀다(원장이 이미 새 코드를 만들었다).
     * 만료된 뒤에 새 코드로 바뀐 것은 만료 알림 대상이다.
     */
    @Query("select i from ClassHomeroomInvite i join fetch i.classGroup c join fetch c.organization "
            + "where i.usedAt is null and i.expiresAt > :from and i.expiresAt <= :to "
            + "and (i.revokedAt is null or i.revokedAt >= i.expiresAt)")
    List<ClassHomeroomInvite> findExpiredUnusedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** (from, to] 사이에 만료될, 살아 있는 초대. */
    @Query("select i from ClassHomeroomInvite i join fetch i.classGroup c join fetch c.organization "
            + "where i.usedAt is null and i.revokedAt is null and i.expiresAt > :from and i.expiresAt <= :to")
    List<ClassHomeroomInvite> findLiveExpiringBetween(@Param("from") Instant from, @Param("to") Instant to);
}
