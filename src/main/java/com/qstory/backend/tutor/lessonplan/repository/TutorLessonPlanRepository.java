package com.qstory.backend.tutor.lessonplan.repository;

import com.qstory.backend.tutor.lessonplan.entity.TutorLessonPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TutorLessonPlanRepository extends JpaRepository<TutorLessonPlan, UUID> {

    /** TutorLessonPlanResponse.of()가 행마다 학생 이름을 읽으므로 N+1을 피하려고 함께 가져온다. */
    @EntityGraph(attributePaths = "tutorStudent")
    List<TutorLessonPlan> findByTutor_IdOrderByAddedAtDesc(UUID tutorId);

    List<TutorLessonPlan> findByTutorStudent_IdOrderByAddedAtDesc(UUID tutorStudentId);

    Optional<TutorLessonPlan> findByTutorStudent_IdAndStoryId(UUID tutorStudentId, String storyId);

    Optional<TutorLessonPlan> findByIdAndTutor_Id(UUID id, UUID tutorId);
}
