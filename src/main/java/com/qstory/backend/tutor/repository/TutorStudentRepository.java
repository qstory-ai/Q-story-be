package com.qstory.backend.tutor.repository;

import com.qstory.backend.tutor.entity.TutorStudent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TutorStudentRepository extends JpaRepository<TutorStudent, UUID> {

    List<TutorStudent> findByTutor_IdOrderByCreatedAtAsc(UUID tutorId);

    Optional<TutorStudent> findByIdAndTutor_Id(UUID id, UUID tutorId);

    /** 반 수업을 만들 때 그 반의 학생을 참여 학생으로 자동 채운다(LessonService). */
    List<TutorStudent> findByClassGroup_IdAndTutor_IdOrderByCreatedAtAsc(UUID classGroupId, UUID tutorId);
}
