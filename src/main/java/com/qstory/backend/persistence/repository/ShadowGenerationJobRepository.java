package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.ShadowGenerationJob;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShadowGenerationJobRepository extends JpaRepository<ShadowGenerationJob, UUID> {

    Optional<ShadowGenerationJob> findByCandidate_Id(UUID candidateId);
}
