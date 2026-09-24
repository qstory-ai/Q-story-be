package com.qstory.backend.tutor.repository;

import com.qstory.backend.tutor.entity.TutorInvite;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TutorInviteRepository extends JpaRepository<TutorInvite, UUID> {
    Optional<TutorInvite> findByTokenHash(String tokenHash);
    Optional<TutorInvite> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);

    /**
     * 조건부 소진 - usedAt이 아직 null일 때만 1행이 바뀐다. 0이면 이 초대는 그 사이 다른 수락이 먼저
     * 소진한 것이라 호출자가 거절해야 한다(읽고-검사하고-쓰기 사이의 경합을 DB가 막는다).
     */
    @Modifying
    @Query("update TutorInvite i set i.usedAt = :now where i.id = :id and i.usedAt is null")
    int markUsed(@Param("id") UUID id, @Param("now") Instant now);

    /** 수락 시 같은 학생의 다른 미사용 초대를 함께 닫는다 - 두 번째 부모가 나중에 덮어쓰지 못하게. */
    @Modifying
    @Query("update TutorInvite i set i.usedAt = :now where i.tutorStudent.id = :studentId and i.usedAt is null")
    int closeOpenInvites(@Param("studentId") UUID studentId, @Param("now") Instant now);
}
