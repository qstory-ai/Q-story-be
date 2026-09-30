package com.qstory.backend.voiceresearch.repository;

import com.qstory.backend.voiceresearch.entity.VoiceResearchConsent;
import com.qstory.backend.voiceresearch.entity.VoiceResearchPreference;
import com.qstory.backend.voiceresearch.entity.VoiceResearchSample;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** VoiceResearchService를 위해 VoiceResearchConsentRepository/VoiceResearchSampleRepository를 감싼다. */
@Component
public class VoiceResearchRepository {

    private final VoiceResearchConsentRepository consentRepository;
    private final VoiceResearchSampleRepository sampleRepository;
    private final VoiceResearchPreferenceRepository preferenceRepository;

    public VoiceResearchRepository(
            VoiceResearchConsentRepository consentRepository, VoiceResearchSampleRepository sampleRepository,
            VoiceResearchPreferenceRepository preferenceRepository) {
        this.consentRepository = consentRepository;
        this.sampleRepository = sampleRepository;
        this.preferenceRepository = preferenceRepository;
    }

    public VoiceResearchConsent findConsent(UUID consentId) {
        return consentRepository.findById(consentId).orElse(null);
    }

    public VoiceResearchConsent saveConsent(VoiceResearchConsent consent) {
        return consentRepository.save(consent);
    }

    public void deleteConsent(VoiceResearchConsent consent) {
        consentRepository.delete(consent);
    }

    public VoiceResearchSample saveSample(VoiceResearchSample sample) {
        return sampleRepository.save(sample);
    }

    public List<VoiceResearchSample> samplesForConsent(UUID consentId) {
        return sampleRepository.findByConsent_Id(consentId);
    }

    public List<VoiceResearchConsent> expiredConsents(Instant before) {
        return consentRepository.findTop200ByExpiresAtBefore(before);
    }

    public List<VoiceResearchConsent> consentsOfUser(UUID userId) {
        return consentRepository.findByUserId(userId);
    }

    public VoiceResearchPreference findPreference(UUID userId) {
        return preferenceRepository.findById(userId).orElse(null);
    }

    public VoiceResearchPreference savePreference(VoiceResearchPreference preference) {
        return preferenceRepository.save(preference);
    }
}
