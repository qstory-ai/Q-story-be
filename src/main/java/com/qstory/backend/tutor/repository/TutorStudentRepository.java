package com.qstory.backend.tutor.repository;

import com.qstory.backend.tutor.entity.TutorStudent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TutorStudentRepository extends JpaRepository<TutorStudent, UUID> {

    List<TutorStudent> findByTutor_IdOrderByCreatedAtAsc(UUID tutorId);

    Optional<TutorStudent> findByIdAndTutor_Id(UUID id, UUID tutorId);
}
