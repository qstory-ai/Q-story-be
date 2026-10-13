package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.Organization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    boolean existsByPublicInstitutionId(UUID publicInstitutionId);

    Optional<Organization> findByPublicInstitutionId(UUID publicInstitutionId);
}
