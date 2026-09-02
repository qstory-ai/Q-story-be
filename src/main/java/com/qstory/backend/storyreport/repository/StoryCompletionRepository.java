package com.qstory.backend.storyreport.repository;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryCompletionRepository extends JpaRepository<StoryCompletion, UUID> {

    List<StoryCompletion> findByUser_IdOrderByCompletedAtDesc(UUID userId);

    /** 최근 N회 누적 트렌드 계산용 - user_id, completed_at desc 복합 인덱스로 커버된다. */
    List<StoryCompletion> findByUser_IdOrderByCompletedAtDesc(UUID userId, Pageable pageable);

    /** 특정 아이(child)에 귀속된 완주만 - 리포트 페이지의 '아이별' 필터에서 사용. */
    List<StoryCompletion> findByUser_IdAndChild_IdOrderByCompletedAtDesc(UUID userId, UUID childId);

    List<StoryCompletion> findByUser_IdAndChild_IdOrderByCompletedAtDesc(
            UUID userId, UUID childId, Pageable pageable);

    Optional<StoryCompletion> findByIdAndUser_Id(UUID id, UUID userId);

    /** 선생님 자신이 진행한, 특정 학생과의 세션들 - TutorController가 그 학생을 소유했는지 먼저 확인한 뒤 호출한다. */
    List<StoryCompletion> findByTutorStudent_IdOrderByCompletedAtDesc(UUID tutorStudentId);

    /**
     * 부모가 받는 "선생님에게 받은 기록" - tutor_student.linked_parent_user_id로 조인, 가정 완주 기록(tutorStudent=null)은 절대 섞이지 않는다.
     * TutorReportSummary.of()가 row마다 tutorStudent/tutorStudent.tutor를 읽으므로, N+1을 피하려고
     * 한 쿼리로 함께 가져온다.
     */
    @EntityGraph(attributePaths = {"tutorStudent", "tutorStudent.tutor"})
    List<StoryCompletion> findByTutorStudent_LinkedParentUser_IdOrderByCompletedAtDesc(UUID linkedParentUserId);

    /** 기관 전체 완주 수 - 이용 현황 요약용. 기관에 속한 사용자(PARENT/CLASS_ACCOUNT)의 완주만. */
    long countByUser_Organization_Id(UUID organizationId);

    /** 기관 전체 최근 완주 목록 - 이용 현황 최근 활동 카드용. */
    List<StoryCompletion> findByUser_Organization_IdOrderByCompletedAtDesc(UUID organizationId, Pageable pageable);
}
