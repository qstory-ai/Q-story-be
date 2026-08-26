package com.qstory.backend.tutor.repository;

import com.qstory.backend.tutor.entity.TutorSchedule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TutorScheduleRepository extends JpaRepository<TutorSchedule, UUID> {

    List<TutorSchedule> findByTutorStudent_IdOrderByCreatedAtAsc(UUID tutorStudentId);

    List<TutorSchedule> findByTutorStudent_Tutor_IdOrderByCreatedAtAsc(UUID tutorId);
}
