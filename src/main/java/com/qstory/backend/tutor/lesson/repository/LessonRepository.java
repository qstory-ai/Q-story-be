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

    /** "향후 모든 수업 수정" 대상 조회 - 아직 안 지난(SCHEDULED) 같은 시리즈의 형제들. */
    List<Lesson> findBySeriesIdAndTutor_IdAndStatus(UUID seriesId, UUID tutorId, LessonStatus status);

    /** 반 학생이 바뀔 때 그 반의 예정 수업 참여자를 맞추기 위해(TutorStudentService). */
    List<Lesson> findByTutor_IdAndClassGroup_IdAndStatus(UUID tutorId, UUID classGroupId, LessonStatus status);

    /** 학생 삭제 시 예정 수업에서 빼기 위해(TutorStudentService). */
    List<Lesson> findByTutor_IdAndStatusAndStudents_Id(UUID tutorId, LessonStatus status, UUID studentId);

    /** 기관에서 선생님을 내보낼 때 그 기관 반에 묶인 예정 수업(OrganizationTutorService). */
    List<Lesson> findByTutor_IdAndClassGroup_Organization_IdAndStatus(UUID tutorId, UUID organizationId, LessonStatus status);

    /** LessonReminderScheduler 전용 - 아직 시작 안 한(SCHEDULED) 수업 중 곧 시작하는 것들. */
    List<Lesson> findByStatusAndScheduledAtBetween(LessonStatus status, Instant from, Instant to);
}
