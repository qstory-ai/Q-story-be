package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.ClassGroup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClassGroupRepository extends JpaRepository<ClassGroup, UUID> {

    Optional<ClassGroup> findByJoinCode(String joinCode);

    List<ClassGroup> findByOrganization_IdOrderByCreatedAtAsc(UUID organizationId);

    boolean existsByJoinCode(String joinCode);

    long countByOrganization_Id(UUID organizationId);

    /** 기관 안에서 이 선생님이 담임인 반 - 소속 해제 시 담임 미정으로 되돌린다(OrganizationTutorService). */
    List<ClassGroup> findByOrganization_IdAndTutor_Id(UUID organizationId, UUID tutorId);

    /** 선생님이 담임인 반(TutorClassService). */
    List<ClassGroup> findByTutor_IdOrderByCreatedAtAsc(UUID tutorId);

    Optional<ClassGroup> findByIdAndTutor_Id(UUID id, UUID tutorId);

    /** 회원 탈퇴 - 기관 없이 선생님이 소유한 반. 기관 반은 detachTutor가 담임만 비운다. */
    @Modifying
    @Query("delete from ClassGroup g where g.tutor.id = :tutorId and g.organization is null")
    int deletePersonalClassesOf(@Param("tutorId") UUID tutorId);
}
