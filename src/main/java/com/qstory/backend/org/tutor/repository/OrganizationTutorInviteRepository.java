package com.qstory.backend.org.tutor.repository;

import com.qstory.backend.org.tutor.entity.OrganizationTutorInvite;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationTutorInviteRepository extends JpaRepository<OrganizationTutorInvite, UUID> {

    Optional<OrganizationTutorInvite> findByTokenHash(String tokenHash);

    Optional<OrganizationTutorInvite> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    List<OrganizationTutorInvite> findByOrganization_IdOrderByCreatedAtDesc(UUID organizationId);
}
