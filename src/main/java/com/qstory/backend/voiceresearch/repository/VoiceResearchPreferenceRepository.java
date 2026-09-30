package com.qstory.backend.voiceresearch.repository;

import com.qstory.backend.voiceresearch.entity.VoiceResearchPreference;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceResearchPreferenceRepository extends JpaRepository<VoiceResearchPreference, UUID> {}
