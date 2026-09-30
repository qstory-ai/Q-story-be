package com.qstory.backend.tutor.repository;

import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.tutor.entity.TutorStudent;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 학생은 소프트 삭제(053)라 모든 조회가 deletedAt is null로 거른다 - 필터 없는 메서드를 두지 않는다. */
public interface TutorStudentRepository extends JpaRepository<TutorStudent, UUID> {

    List<TutorStudent> findByTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID tutorId);

    Optional<TutorStudent> findByIdAndTutor_IdAndDeletedAtIsNull(UUID id, UUID tutorId);

    /** 수업 참여 학생을 한 번에 확인한다 - 이 선생님의 학생이 아닌 id는 결과에서 빠진다(LessonService). */
    List<TutorStudent> findByIdInAndTutor_IdAndDeletedAtIsNull(Collection<UUID> ids, UUID tutorId);

    /** 반 수업을 만들 때 그 반의 학생을 참여 학생으로 자동 채운다(LessonService). */
    List<TutorStudent> findByClassGroup_IdAndTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID classGroupId, UUID tutorId);

    /** 반 상세의 학생 명단 - 담임이 없는 반의 학생도 포함한다. */
    List<TutorStudent> findByClassGroup_IdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID classGroupId);

    /** 기관 리포트의 반별 인원수 - [classGroupId, count] 행. 학생이 없는 반은 결과에 없다. */
    @Query("select s.classGroup.id, count(s) from TutorStudent s "
            + "where s.classGroup.organization.id = :organizationId and s.deletedAt is null group by s.classGroup.id")
    List<Object[]> countByClassGroupInOrganization(@Param("organizationId") UUID organizationId);

    /** 담임이 배정되기 전에 들어온 학생 - 배정하면 이 학생들이 담임의 학생이 된다(ClassService.assignHomeroom). */
    List<TutorStudent> findByClassGroup_IdAndTutorIsNullAndDeletedAtIsNull(UUID classGroupId);

    boolean existsByClassGroup_IdAndChild_IdAndDeletedAtIsNull(UUID classGroupId, UUID childId);

    /** 학부모가 자기 아이가 들어가 있는 반을 볼 때(ClassService.listMemberships). */
    List<TutorStudent> findByLinkedParentUser_IdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID parentUserId);

    /** 기관 사용 현황 - 기관 반에 올라 있는 학생 수. */
    long countByClassGroup_Organization_IdAndDeletedAtIsNull(UUID organizationId);

    /** 기관 사용 현황 - 기관 반 학생에 연결된 학부모 수(중복 제외). */
    @Query("select count(distinct s.linkedParentUser.id) from TutorStudent s "
            + "where s.classGroup.organization.id = :organizationId and s.deletedAt is null and s.linkedParentUser is not null")
    long countLinkedParentsByOrganization(@Param("organizationId") UUID organizationId);

    /** 학부모의 이용권 근거 - 이 부모의 아이가 들어가 있는 기관 반과 그 기관(EntitlementService). */
    @Query("select new com.qstory.backend.tutor.repository.ParentClassSeat(s.id, coalesce(s.linkedAt, s.createdAt), o) "
            + "from TutorStudent s join s.classGroup c join c.organization o "
            + "where s.linkedParentUser.id = :parentUserId and s.deletedAt is null")
    List<ParentClassSeat> findClassSeatsOfParent(@Param("parentUserId") UUID parentUserId);

    /**
     * 기관 이용권의 과금 대상 - 기관 반에 올라 있고 학부모가 연결된 학생. 기관 이용권의 혜택(학부모의 이야기 이용)은
     * 학부모가 연결돼야 생기므로, 초대만 받고 연결 전이거나 학부모가 반에서 뺀 학생은 세지 않는다.
     */
    long countByClassGroup_Organization_IdAndDeletedAtIsNullAndLinkedParentUserIsNotNull(UUID organizationId);

    /**
     * 기관 안에서 이 학생보다 먼저 학부모가 연결된 과금 대상 학생 수 - 결제한 인원 안에 드는지(순번) 판단한다. 등록
     * 순서가 아니라 연결 순서라서, 오래전에 등록만 해 둔 학생이 나중에 연결돼도 이미 이용 중인 학부모를 밀어내지 않는다.
     */
    @Query("select count(s) from TutorStudent s where s.classGroup.organization.id = :organizationId "
            + "and s.deletedAt is null and s.linkedParentUser is not null "
            + "and (coalesce(s.linkedAt, s.createdAt) < :linkedAt "
            + "or (coalesce(s.linkedAt, s.createdAt) = :linkedAt and s.id < :studentId))")
    long countEarlierInOrganization(
            @Param("organizationId") UUID organizationId, @Param("linkedAt") Instant linkedAt,
            @Param("studentId") UUID studentId);

    /** 반 코드로 들어온 학부모를 선생님이 미리 올려 둔(아직 학부모가 없는) 학생에 잇기 위한 후보. */
    List<TutorStudent> findByClassGroup_IdAndLinkedParentUserIsNullAndDeletedAtIsNull(UUID classGroupId);

    boolean existsByTutor_IdAndChild_IdAndDeletedAtIsNull(UUID tutorId, UUID childId);

    /** 부모 계정 탈퇴 시 연결을 풀어 학생을 다시 초대 가능한 상태로 되돌린다(AuthService). */
    List<TutorStudent> findByLinkedParentUser_Id(UUID parentUserId);

    /** 기관에서 선생님을 내보낼 때 그 기관 반에 들어 있던 학생(OrganizationTutorService). */
    List<TutorStudent> findByTutor_IdAndClassGroup_Organization_IdAndDeletedAtIsNull(UUID tutorId, UUID organizationId);

    /**
     * 초대 수락 시 학생 행을 잠근다 - 같은 학생의 초대를 두 보호자가 동시에 수락하면 둘 다 검사를
     * 통과해 아이 프로필이 두 개 생기던 경합을 직렬화한다. 잠금은 트랜잭션이 끝날 때 풀린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TutorStudent s where s.id = :id")
    Optional<TutorStudent> lockById(@Param("id") UUID id);
}
