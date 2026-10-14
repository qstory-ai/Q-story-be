package com.qstory.backend.org.joinrequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationJoinRequestRepository extends JpaRepository<OrganizationJoinRequest, UUID> {

    Optional<OrganizationJoinRequest> findFirstByOrganization_IdAndTutor_IdAndStatus(
            UUID organizationId, UUID tutorId, OrganizationJoinRequest.Status status);

    @Query("select r from OrganizationJoinRequest r join fetch r.tutor "
            + "where r.organization.id = :organizationId and r.status = :status order by r.createdAt asc")
    List<OrganizationJoinRequest> findForOrganization(
            @Param("organizationId") UUID organizationId, @Param("status") OrganizationJoinRequest.Status status);

    /** 선생님의 신청 - 최근 것부터 20건(오래된 처리 기록은 화면에 필요 없다). */
    @EntityGraph(attributePaths = "organization")
    List<OrganizationJoinRequest> findTop20ByTutor_IdOrderByCreatedAtDesc(UUID tutorId);
}
