package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.ClassGroup;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassGroupRepository extends JpaRepository<ClassGroup, UUID> {

    Optional<ClassGroup> findByJoinCode(String joinCode);

    List<ClassGroup> findByOrganization_IdOrderByCreatedAtAsc(UUID organizationId);

    boolean existsByJoinCode(String joinCode);

    long countByOrganization_Id(UUID organizationId);

    /** 기관 안에서 이 선생님이 만든 반 - 소속 해제 시 기관 소유로 넘긴다(OrganizationTutorService). */
    List<ClassGroup> findByOrganization_IdAndTutor_Id(UUID organizationId, UUID tutorId);

    /** 선생님이 직접 만든 반(TutorClassService). */
    List<ClassGroup> findByTutor_IdOrderByCreatedAtAsc(UUID tutorId);

    /** 선생님이 소속된 여러 기관의 반을 한 번에(TutorClassService.visibleClasses). */
    List<ClassGroup> findByOrganization_IdInOrderByCreatedAtAsc(Collection<UUID> organizationIds);
}
