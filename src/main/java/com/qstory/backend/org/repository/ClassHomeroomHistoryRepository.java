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

    /** 이 선생님이 이 반의 담임이었던 적이 있는가(지금 담임 포함) - 지난 담임의 열람 권한(076). */
    boolean existsByClassGroup_IdAndTutor_Id(UUID classGroupId, UUID tutorId);

    /** 이 선생님의 담임 이력 전체 - 예전에 맡았던 반 목록(076, TutorClassService.listPast). */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"classGroup", "classGroup.organization", "classGroup.tutor"})
    List<ClassHomeroomHistory> findByTutor_IdOrderByStartedAtAsc(UUID tutorId);

    /** 회원 탈퇴 - 기관 밖 반의 담임 이력. 기관 반 이력은 남긴다. */
    @Modifying
    @Query("delete from ClassHomeroomHistory h where h.tutor.id = :tutorId and h.classGroup.id in (select g.id from ClassGroup g where g.organization is null)")
    int deletePersonalHistoryOf(@Param("tutorId") UUID tutorId);
}
