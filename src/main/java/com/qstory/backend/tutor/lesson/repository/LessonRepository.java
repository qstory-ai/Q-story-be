package com.qstory.backend.tutor.lesson.repository;

import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonRepository extends JpaRepository<Lesson, UUID> {

    List<Lesson> findByTutor_IdOrderByScheduledAtAscCreatedAtAsc(UUID tutorId);

    List<Lesson> findByTutor_IdAndStatusOrderByScheduledAtAscCreatedAtAsc(UUID tutorId, LessonStatus status);

    Optional<Lesson> findByIdAndTutor_Id(UUID id, UUID tutorId);
}
