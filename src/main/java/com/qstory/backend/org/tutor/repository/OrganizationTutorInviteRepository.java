package com.qstory.backend.org.tutor.repository;

import com.qstory.backend.org.tutor.entity.OrganizationTutorInvite;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationTutorInviteRepository extends JpaRepository<OrganizationTutorInvite, UUID> {

    Optional<OrganizationTutorInvite> findByTokenHash(String tokenHash);

    Optional<OrganizationTutorInvite> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    List<OrganizationTutorInvite> findByOrganization_IdOrderByCreatedAtDesc(UUID organizationId);

    /** (from, to] 사이에 만료된, 쓰지 않은 초대 - 원장에게 만료 알림을 보낸다. */
    @Query("select i from OrganizationTutorInvite i join fetch i.organization "
            + "where i.usedAt is null and i.expiresAt > :from and i.expiresAt <= :to")
    List<OrganizationTutorInvite> findExpiredUnusedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
