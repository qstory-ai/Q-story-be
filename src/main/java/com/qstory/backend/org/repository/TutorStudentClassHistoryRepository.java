package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.TutorStudentClassHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TutorStudentClassHistoryRepository extends JpaRepository<TutorStudentClassHistory, Long> {

    /** 지금 반 구간(ended_at null) - 학생마다 많아야 하나(076 부분 유니크 인덱스). */
    List<TutorStudentClassHistory> findByTutorStudent_IdAndEndedAtIsNull(UUID tutorStudentId);

    /** 학생의 반 이력 - 오래된 것부터. */
    @EntityGraph(attributePaths = {"classGroup"})
    List<TutorStudentClassHistory> findByTutorStudent_IdOrderByStartedAtAscIdAsc(UUID tutorStudentId);

    /** 이 반에서 끝난 구간(지난 학생) - 최근에 끝난 것부터. 지워진 학생은 뺀다. */
    @EntityGraph(attributePaths = {"tutorStudent", "tutorStudent.linkedParentUser"})
    @Query("select h from TutorStudentClassHistory h where h.classGroup.id = :classId and h.endedAt is not null "
            + "and h.tutorStudent.deletedAt is null order by h.endedAt desc, h.id desc")
    List<TutorStudentClassHistory> findEndedInClass(@Param("classId") UUID classId);
}
