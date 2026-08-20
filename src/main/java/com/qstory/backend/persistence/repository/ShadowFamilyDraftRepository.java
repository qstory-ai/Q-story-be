package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.ShadowFamilyDraft;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShadowFamilyDraftRepository extends JpaRepository<ShadowFamilyDraft, UUID> {

    Optional<ShadowFamilyDraft> findByCandidate_Id(UUID candidateId);
}
