package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.ClassGroup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassGroupRepository extends JpaRepository<ClassGroup, UUID> {

    Optional<ClassGroup> findByJoinCode(String joinCode);

    List<ClassGroup> findByOrganization_IdOrderByCreatedAtAsc(UUID organizationId);

    boolean existsByJoinCode(String joinCode);

    long countByOrganization_Id(UUID organizationId);

    /** 기관 안에서 이 선생님이 만든 반 - 소속 해제 시 담임 미정으로 되돌린다(OrganizationTutorService). */
    List<ClassGroup> findByOrganization_IdAndTutor_Id(UUID organizationId, UUID tutorId);

    /** 선생님이 직접 만든 반(TutorClassService). */
    List<ClassGroup> findByTutor_IdOrderByCreatedAtAsc(UUID tutorId);
}
