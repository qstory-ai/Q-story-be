package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.ClassHomeroomInvite;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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

    /** 지금 쓸 수 있는(쓰지 않았고 만료되지 않은) 초대. */
    Optional<ClassHomeroomInvite> findFirstByClassGroup_IdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID classGroupId, Instant now);

    /**
     * 새로 발급하기 전에 쓰지 않은 이전 초대(만료된 것 포함)를 지운다. 바로 실행되는 일괄 삭제라 같은 트랜잭션에서
     * 새 초대를 넣어도 부분 유니크 인덱스(class_group_id where used_at is null)에 걸리지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from ClassHomeroomInvite i where i.classGroup.id = :classGroupId and i.usedAt is null")
    int deleteUnusedByClassGroupId(@Param("classGroupId") UUID classGroupId);
}
