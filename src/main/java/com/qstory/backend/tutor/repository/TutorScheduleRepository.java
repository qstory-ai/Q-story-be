package com.qstory.backend.tutor.repository;

import com.qstory.backend.tutor.entity.TutorSchedule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TutorScheduleRepository extends JpaRepository<TutorSchedule, UUID> {

    List<TutorSchedule> findByTutorStudent_IdOrderByCreatedAtAsc(UUID tutorStudentId);

    /** TutorScheduleResponse.of()가 row마다 tutorStudent를 읽으므로, N+1을 피하려고 한 쿼리로 함께 가져온다. */
    @EntityGraph(attributePaths = "tutorStudent")
    List<TutorSchedule> findByTutorStudent_Tutor_IdOrderByCreatedAtAsc(UUID tutorId);
}
