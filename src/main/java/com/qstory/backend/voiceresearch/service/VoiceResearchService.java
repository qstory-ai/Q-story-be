package com.qstory.backend.voiceresearch.service;
import com.qstory.backend.voiceresearch.util.VoiceResearchValidator;
import com.qstory.backend.voiceresearch.repository.VoiceResearchRepository;

import com.qstory.backend.common.error.EdgeErrorCode;
import com.qstory.backend.common.error.EdgeException;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.voiceresearch.entity.VoiceResearchConsent;
import com.qstory.backend.voiceresearch.entity.VoiceResearchSample;
import com.qstory.backend.voiceresearch.dto.UploadRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Java port of supabase/functions/voice-research/index.ts. */
@Service
public class VoiceResearchService {

    public static final String CONSENT_VERSION = "voice-research-v2-shadow-family";
    private static final Duration RETENTION = Duration.ofDays(90);

    private final VoiceResearchValidator validator;
    private final VoiceResearchRepository repository;
    private final SupabaseStorageClient storageClient;
    private final String bucket;

    public VoiceResearchService(
            VoiceResearchValidator validator, VoiceResearchRepository repository,
            SupabaseStorageClient storageClient, AppProperties config) {
        this.validator = validator;
        this.repository = repository;
        this.storageClient = storageClient;
        this.bucket = config.supabase().voiceResearchBucket();
    }

    @Transactional
    public void upload(UploadRequest request) {
        validator.validate(request);
        String deletionTokenHash = DigestUtil.sha256Hex(request.deletionToken());
        VoiceResearchConsent consent = ensureConsent(request, deletionTokenHash);

        String extension = extensionFor(request.audio().getContentType());
        String objectName = request.consentId() + "/" + request.sampleId() + "." + extension;
        byte[] audioBytes;
        try {
            audioBytes = request.audio().getBytes();
        } catch (Exception readError) {
            throw new EdgeException(EdgeErrorCode.INVALID_FORM_DATA);
        }
        if (!storageClient.upload(bucket, objectName, audioBytes, request.audio().getContentType())) {
            throw new EdgeException(EdgeErrorCode.STORAGE_FAILED, 500, "audio_upload");
        }

        try {
            repository.saveSample(VoiceResearchSample.builder()
                    .id(request.sampleId())
                    .consent(consent)
                    .storageObjectName(objectName)
                    .storyId(request.storyId())
                    .sceneId(request.sceneId())
                    .anchorId(request.anchorId())
                    .questionRound(request.questionRound())
                    .mimeType(request.audio().getContentType())
                    .byteSize(audioBytes.length)
                    .durationMillis(request.durationMillis())
                    .sttDraft(request.sttDraft())
                    .confirmedTranscript(request.confirmedTranscript())
                    .coverageStatus(request.coverageStatus())
                    .routedFamilyId(request.familyId())
                    .intentSummary(request.intentSummary())
                    .outcomeRecordedAt(request.hasRouteOutcome() ? Instant.now() : null)
                    .createdAt(Instant.now())
                    .build());
        } catch (RuntimeException persistError) {
            storageClient.delete(bucket, objectName);
            throw new EdgeException(EdgeErrorCode.STORAGE_FAILED, 500, "sample_create");
        }
    }

    private VoiceResearchConsent ensureConsent(UploadRequest request, String deletionTokenHash) {
        VoiceResearchConsent consent = repository.findConsent(request.consentId());
        if (consent == null) {
            Instant expiresAt = request.consentedAt().plus(RETENTION);
            consent = repository.saveConsent(VoiceResearchConsent.builder()
                    .id(request.consentId())
                    .deletionTokenHash(deletionTokenHash)
                    .consentVersion(CONSENT_VERSION)
                    .consentedAt(request.consentedAt())
                    .expiresAt(expiresAt)
                    .createdAt(Instant.now())
                    .build());
        }
        if (!CONSENT_VERSION.equals(consent.getConsentVersion())
                || !DigestUtil.constantTimeEquals(consent.getDeletionTokenHash(), deletionTokenHash)
                || consent.getExpiresAt().isBefore(Instant.now())) {
            throw new EdgeException(EdgeErrorCode.CONSENT_INVALID);
        }
        return consent;
    }

    @Transactional
    public void withdraw(UUID consentId, String deletionToken) {
        VoiceResearchConsent consent = repository.findConsent(consentId);
        if (consent == null || !DigestUtil.constantTimeEquals(consent.getDeletionTokenHash(), DigestUtil.sha256Hex(deletionToken))) {
            throw new EdgeException(EdgeErrorCode.CONSENT_INVALID);
        }
        deleteConsentAndSamples(consent);
    }

    /** Called by a scheduled retention sweep instead of the original pg_cron -> edge-function hop. */
    @Transactional
    public int cleanupExpired() {
        List<VoiceResearchConsent> expired = repository.expiredConsents(Instant.now());
        expired.forEach(this::deleteConsentAndSamples);
        return expired.size();
    }

    private void deleteConsentAndSamples(VoiceResearchConsent consent) {
        List<VoiceResearchSample> samples = repository.samplesForConsent(consent.getId());
        samples.forEach(sample -> storageClient.delete(bucket, sample.getStorageObjectName()));
        repository.deleteConsent(consent);
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) {
            return "bin";
        }
        return switch (mimeType) {
            case "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a";
            default -> "webm";
        };
    }
}
