package com.qstory.backend.storyreport.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryCompletionRepository extends JpaRepository<StoryCompletion, UUID> {

    List<StoryCompletion> findByUser_IdOrderByCompletedAtDesc(UUID userId);

    /** 최근 N회 누적 트렌드 계산용 - user_id, completed_at desc 복합 인덱스로 커버된다. */
    List<StoryCompletion> findByUser_IdOrderByCompletedAtDesc(UUID userId, Pageable pageable);

    /** 수업 하나의 완주 기록 - LessonController가 수업 소유를 먼저 확인한 뒤 호출한다. */
    List<StoryCompletion> findByLesson_IdOrderByCompletedAtDesc(UUID lessonId);

    /** 특정 학생이 참여한 선생님 세션(개별·반 수업) - TutorController가 그 학생을 소유했는지 먼저 확인한 뒤 호출한다. */
    @Query("select c from StoryCompletion c join c.participants p where p.id = :studentId order by c.completedAt desc")
    List<StoryCompletion> findByParticipant(@Param("studentId") UUID studentId);

    /**
     * 부모가 받는 "수업 리포트"의 (기록 id, 우리 아이 이름) - 참여 학생의 지금 연결된 부모이거나, 연결을 풀 때
     * 남겨 둔 부모(060 parent_user_id)면 보인다. 가정 완주 기록(참여 학생 없음)은 절대 섞이지 않는다.
     */
    @Query(value = "select cast(p.completion_id as varchar), s.name from story_completion_participant p "
            + "join tutor_student s on s.id = p.tutor_student_id "
            + "where s.linked_parent_user_id = :parentId or p.parent_user_id = :parentId", nativeQuery = true)
    List<Object[]> findVisibleParticipantNames(@Param("parentId") UUID parentId);

    @EntityGraph(attributePaths = {"user", "classGroup", "classGroup.organization"})
    List<StoryCompletion> findByIdInOrderByCompletedAtDesc(java.util.Collection<UUID> ids);

    /** 상세 열람 권한 - 호출자가 이 세션 참여 학생의 연결된(또는 연결을 풀기 전 연결됐던) 부모인가. */
    @Query(value = "select exists (select 1 from story_completion_participant p "
            + "join tutor_student s on s.id = p.tutor_student_id "
            + "where p.completion_id = :completionId "
            + "and (s.linked_parent_user_id = :parentId or p.parent_user_id = :parentId))", nativeQuery = true)
    boolean isVisibleToLinkedParent(@Param("completionId") UUID completionId, @Param("parentId") UUID parentId);

    /**
     * 학부모가 아이를 반에서 뺄 때 - 그 학생이 참여한 지난 수업마다 이 학부모를 남겨, 연결을 풀어도 리포트는 계속
     * 보이게 한다. 이미 다른 학부모가 남아 있는 행은 건드리지 않는다.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "update story_completion_participant set parent_user_id = :parentId "
            + "where tutor_student_id = :studentId and parent_user_id is null", nativeQuery = true)
    int keepParentAccess(@Param("studentId") UUID studentId, @Param("parentId") UUID parentId);

    /** 부모의 아이별 가정 기록 - 선생님 수업은 "수업 리포트"에서 따로 보므로 여기 섞지 않는다. */
    List<StoryCompletion> findByUser_IdAndChild_IdOrderByCompletedAtDesc(UUID userId, UUID childId);

    List<StoryCompletion> findByUser_IdAndChild_IdOrderByCompletedAtDesc(UUID userId, UUID childId, Pageable pageable);

    /** 멱등 저장 - 같은 세션(conversationId)으로 이미 저장된 기록(053). 호출자 본인 것만. */
    List<StoryCompletion> findBySessionIdAndUser_IdOrderByCreatedAtAsc(UUID sessionId, UUID userId);

    /** 기관 전체 완주 수 - 저장 시점에 스냅샷된 organization_id 기준(선생님의 기관 반 수업 포함). */
    long countByOrganization_Id(UUID organizationId);
    /** 기관 전체 최근 완주 목록 - 이용 현황 최근 활동 카드용. 같은 스냅샷 기준. */
    @EntityGraph(attributePaths = {"user", "tutorStudent", "classGroup"})
    List<StoryCompletion> findByOrganization_IdOrderByCompletedAtDesc(UUID organizationId, Pageable pageable);

    /** Full organization aggregate report. The graph prevents one query per completion/class membership. */
    @EntityGraph(attributePaths = {"classGroup"})
    List<StoryCompletion> findByOrganization_IdOrderByCompletedAtDesc(UUID organizationId);
}
