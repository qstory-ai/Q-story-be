package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.VoiceResearchConsent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceResearchConsentRepository extends JpaRepository<VoiceResearchConsent, UUID> {

    List<VoiceResearchConsent> findTop200ByExpiresAtBefore(Instant cutoff);
}
