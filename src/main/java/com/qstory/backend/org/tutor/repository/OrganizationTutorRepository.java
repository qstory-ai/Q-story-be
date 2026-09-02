package com.qstory.backend.org.tutor.repository;

import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationTutorRepository extends JpaRepository<OrganizationTutor, UUID> {

    List<OrganizationTutor> findByOrganization_IdOrderByJoinedAtAsc(UUID organizationId);

    List<OrganizationTutor> findByTutor_IdOrderByJoinedAtAsc(UUID tutorId);

    Optional<OrganizationTutor> findByOrganization_IdAndTutor_Id(UUID organizationId, UUID tutorId);

    Optional<OrganizationTutor> findByIdAndOrganization_Id(UUID id, UUID organizationId);

    long countByOrganization_Id(UUID organizationId);
}
