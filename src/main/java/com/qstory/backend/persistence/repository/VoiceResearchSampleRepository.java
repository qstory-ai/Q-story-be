package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.VoiceResearchSample;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceResearchSampleRepository extends JpaRepository<VoiceResearchSample, UUID> {

    List<VoiceResearchSample> findByConsent_Id(UUID consentId);

    boolean existsByConsent_IdAndAnchorIdAndQuestionRound(UUID consentId, String anchorId, int questionRound);
}
