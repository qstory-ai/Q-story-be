package com.qstory.backend.storyreport.repository;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 학부모가 아이를 반에서 뺄 때 - 그 학생이 참여한 수업 중 이 학부모가 연결돼 있던 동안(linkedSince 이후)의
     * 기록마다 학부모를 남겨, 연결을 풀어도 그 리포트는 계속 보이게 한다. 연결 전 기록까지 남기면 남의 아이를 잠깐
     * 골라 잇고 빠지는 것만으로 그 아이의 지난 리포트를 영구히 보게 된다. 이미 다른 학부모가 남아 있는 행은 그대로 둔다.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "update story_completion_participant p set parent_user_id = :parentId "
            + "from story_completion c "
            + "where c.id = p.completion_id and p.tutor_student_id = :studentId and p.parent_user_id is null "
            + "and c.completed_at >= :linkedSince", nativeQuery = true)
    int keepParentAccess(
            @Param("studentId") UUID studentId, @Param("parentId") UUID parentId,
            @Param("linkedSince") java.time.Instant linkedSince);

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

    /** 회원 탈퇴 - 보호자의 가정 세션(수업·반 없는 기록). 반 수업 기록은 남긴다. */
    @Modifying
    @Query("delete from StoryCompletion c where c.user.id = :parentId and c.lesson is null and c.classGroup is null")
    int deleteHomeSessionsOf(@Param("parentId") UUID parentId);

    /**
     * 회원 탈퇴 - 반을 떠난 뒤에도 지난 반 수업 리포트를 보도록 남겨 둔 보호자 열람 권한(060)만 비운다. 참여 행
     * 자체는 반 수업 기록의 참여자 명단이라 지우지 않는다.
     */
    @Modifying
    @Query(value = "update story_completion_participant set parent_user_id = null where parent_user_id = :parentId",
            nativeQuery = true)
    int clearParentAccessOf(@Param("parentId") UUID parentId);

    /**
     * 회원 탈퇴 - 기관과 닿지 않는 선생님 기록만. organization 스냅샷이 비어 있어도(백필 전 기록) 기관 반이나 기관
     * 반 수업에 묶인 기록은 남긴다. 반·수업이 없으면 null 비교로 빠지지 않게 "is null or id in (서브쿼리)"로 쓴다.
     */
    @Modifying
    @Query("delete from StoryCompletion c where c.user.id = :tutorId and c.organization is null "
            + "and (c.classGroup is null or c.classGroup.id in "
            + "(select g.id from ClassGroup g where g.organization is null)) "
            + "and (c.lesson is null or c.lesson.id in "
            + "(select l.id from Lesson l where l.classGroup is null or l.classGroup.id in "
            + "(select lg.id from ClassGroup lg where lg.organization is null)))")
    int deletePersonalSessionsOfTutor(@Param("tutorId") UUID tutorId);
}
