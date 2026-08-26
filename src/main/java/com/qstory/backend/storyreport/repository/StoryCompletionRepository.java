package com.qstory.backend.storyreport.repository;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryCompletionRepository extends JpaRepository<StoryCompletion, UUID> {

    List<StoryCompletion> findByUser_IdOrderByCompletedAtDesc(UUID userId);

    Optional<StoryCompletion> findByIdAndUser_Id(UUID id, UUID userId);

    /** 선생님 자신이 진행한, 특정 학생과의 세션들 - TutorController가 그 학생을 소유했는지 먼저 확인한 뒤 호출한다. */
    List<StoryCompletion> findByTutorStudent_IdOrderByCompletedAtDesc(UUID tutorStudentId);

    /** 부모가 받는 "선생님에게 받은 기록" - tutor_student.linked_parent_user_id로 조인, 가정 완주 기록(tutorStudent=null)은 절대 섞이지 않는다. */
    List<StoryCompletion> findByTutorStudent_LinkedParentUser_IdOrderByCompletedAtDesc(UUID linkedParentUserId);
}
