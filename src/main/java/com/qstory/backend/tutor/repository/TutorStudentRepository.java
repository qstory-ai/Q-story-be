package com.qstory.backend.tutor.repository;

import com.qstory.backend.tutor.entity.TutorStudent;
import jakarta.persistence.LockModeType;
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

    /** 반 수업을 만들 때 그 반의 학생을 참여 학생으로 자동 채운다(LessonService). */
    List<TutorStudent> findByClassGroup_IdAndTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID classGroupId, UUID tutorId);

    /** 기관 리포트의 반 인원수 - 반 코드로 가입한 부모(app_user)와 별개로 선생님이 반에 넣은 학생. */
    long countByClassGroup_IdAndDeletedAtIsNull(UUID classGroupId);

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
