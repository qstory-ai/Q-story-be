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

    /** 이용 현황의 선생님 수 - 탈퇴(소프트 삭제)한 선생님은 제외. */
    long countByOrganization_IdAndTutor_DeletedAtIsNull(UUID organizationId);

    /** 선생님 계정 탈퇴 시 소속 관계를 정리한다(AuthService). */
    void deleteByTutor_Id(UUID tutorId);
}
