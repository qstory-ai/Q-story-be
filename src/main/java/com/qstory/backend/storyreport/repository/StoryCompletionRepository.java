package com.qstory.backend.storyreport.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
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

    /** 수업 하나의 완주 기록(참여 학생별로 한 행씩) - LessonController가 수업 소유를 먼저 확인한 뒤 호출한다. */
    List<StoryCompletion> findByLesson_IdOrderByCompletedAtDesc(UUID lessonId);

    /** 선생님 자신이 진행한, 특정 학생과의 세션들 - TutorController가 그 학생을 소유했는지 먼저 확인한 뒤 호출한다. */
    List<StoryCompletion> findByTutorStudent_IdOrderByCompletedAtDesc(UUID tutorStudentId);

    /**
     * 부모가 받는 "선생님에게 받은 기록" - tutor_student.linked_parent_user_id로 조인, 가정 완주 기록(tutorStudent=null)은 절대 섞이지 않는다.
     * TutorReportSummary.of()가 row마다 tutorStudent/tutorStudent.tutor를 읽으므로, N+1을 피하려고
     * 한 쿼리로 함께 가져온다.
     */
    @EntityGraph(attributePaths = {"tutorStudent", "tutorStudent.tutor"})
    List<StoryCompletion> findByTutorStudent_LinkedParentUser_IdOrderByCompletedAtDesc(UUID linkedParentUserId);

    /**
     * 부모의 아이별 조회 - 부모 자신이 가정에서 진행한 기록과, 연결된 선생님이 그 아이(학생↔아이 링크)와
     * 진행한 기록을 함께 본다. 선생님 세션은 user_id가 선생님이라 user_id만으로는 절대 보이지 않는다.
     * left join이어야 한다 - 암묵 조인(c.tutorStudent.linkedParentUser)은 inner join이 되어 가정 기록이 빠진다.
     */
    @Query("select c from StoryCompletion c left join c.tutorStudent ts left join ts.linkedParentUser lp "
            + "where c.child.id = :childId and (c.user.id = :userId or lp.id = :userId) order by c.completedAt desc")
    List<StoryCompletion> findVisibleToParentByChild(@Param("userId") UUID userId, @Param("childId") UUID childId);

    @Query("select c from StoryCompletion c left join c.tutorStudent ts left join ts.linkedParentUser lp "
            + "where c.child.id = :childId and (c.user.id = :userId or lp.id = :userId) order by c.completedAt desc")
    List<StoryCompletion> findVisibleToParentByChild(
            @Param("userId") UUID userId, @Param("childId") UUID childId, Pageable pageable);

    /** 멱등 저장 - 같은 세션(conversationId)으로 이미 저장된 기록(053). 호출자 본인 것만. */
    List<StoryCompletion> findBySessionIdAndUser_IdOrderByCreatedAtAsc(UUID sessionId, UUID userId);

    /** 기관 전체 완주 수 - 저장 시점에 스냅샷된 organization_id 기준(선생님의 기관 반 수업 포함). */
    long countByOrganization_Id(UUID organizationId);
    /** 기관 전체 최근 완주 목록 - 이용 현황 최근 활동 카드용. 같은 스냅샷 기준. */
    @EntityGraph(attributePaths = {"user", "tutorStudent"})
    List<StoryCompletion> findByOrganization_IdOrderByCompletedAtDesc(UUID organizationId, Pageable pageable);

    /** Full organization aggregate report. The graph prevents one query per completion/class membership. */
    @EntityGraph(attributePaths = {"classGroup"})
    List<StoryCompletion> findByOrganization_IdOrderByCompletedAtDesc(UUID organizationId);
}
