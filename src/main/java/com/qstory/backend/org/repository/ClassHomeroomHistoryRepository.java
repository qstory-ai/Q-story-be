package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.ClassHomeroomHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClassHomeroomHistoryRepository extends JpaRepository<ClassHomeroomHistory, UUID> {

    /** 지금 담임 구간(ended_at null) - 반마다 많아야 하나(063 부분 유니크 인덱스). */
    List<ClassHomeroomHistory> findByClassGroup_IdAndEndedAtIsNull(UUID classGroupId);

    List<ClassHomeroomHistory> findByClassGroup_IdOrderByStartedAtAsc(UUID classGroupId);

    /** 회원 탈퇴 - 기관 밖 반의 담임 이력. 기관 반 이력은 남긴다. */
    @Modifying
    @Query("delete from ClassHomeroomHistory h where h.tutor.id = :tutorId and h.classGroup.id in (select g.id from ClassGroup g where g.organization is null)")
    int deletePersonalHistoryOf(@Param("tutorId") UUID tutorId);
}
