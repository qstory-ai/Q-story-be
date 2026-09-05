package com.qstory.backend.tutor.lesson.repository;

import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonRepository extends JpaRepository<Lesson, UUID> {

    List<Lesson> findByTutor_IdOrderByScheduledAtAscCreatedAtAsc(UUID tutorId);

    List<Lesson> findByTutor_IdAndStatusOrderByScheduledAtAscCreatedAtAsc(UUID tutorId, LessonStatus status);

    Optional<Lesson> findByIdAndTutor_Id(UUID id, UUID tutorId);

    /** LessonReminderScheduler 전용 - 아직 시작 안 한(SCHEDULED) 수업 중 곧 시작하는 것들. */
    List<Lesson> findByStatusAndScheduledAtBetween(LessonStatus status, Instant from, Instant to);
}
