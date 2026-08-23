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
}
