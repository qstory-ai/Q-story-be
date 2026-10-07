package com.qstory.backend.voiceresearch.service;
import com.qstory.backend.voiceresearch.util.VoiceResearchValidator;
import com.qstory.backend.voiceresearch.repository.VoiceResearchRepository;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.ConsentRecordRequest;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.service.ConsentService;
import com.qstory.backend.voiceresearch.dto.VoiceResearchConsentStatusResponse;
import com.qstory.backend.voiceresearch.entity.VoiceResearchPreference;
import com.qstory.backend.voiceresearch.entity.VoiceResearchConsent;
import com.qstory.backend.voiceresearch.entity.VoiceResearchSample;
import com.qstory.backend.voiceresearch.dto.UploadRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 음성 연구 녹음의 동의 확인·Storage 업로드·철회·보존 만료 정리.
 *
 * <p>동의는 두 층이다. (1) 세션 동의(VoiceResearchConsent): 이야기 세션마다 브라우저가 만들고, 그
 * 브라우저가 가진 deletion_token으로 철회한다. (2) 계정 동의(VoiceResearchPreference): 로그인한
 * 보호자가 마이페이지에서 켜고 끈다. 계정 동의가 꺼져 있으면 그 계정으로 오는 업로드를 거절하고,
 * 끄는 순간 그 계정에 연결된 세션 동의와 녹음(Storage 객체 포함)을 모두 지운다.
 */
@Service
public class VoiceResearchService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VoiceResearchService.class);

    public static final String CONSENT_VERSION = "voice-research-v3-1y";
    /** 계정 동의 출처 - 온보딩 동의 화면 또는 마이페이지. */
    private static final java.util.Set<String> GRANT_SOURCES = java.util.Set.of("ONBOARDING", "MYPAGE");
    private static final Duration RETENTION = Duration.ofDays(365);
    /** 보호자가 명시적으로 동의하기 전까지는 꺼짐 - 원본 음성은 동의한 보호자 계정에서만 받는다. */
    static final boolean DEFAULT_ENABLED = false;

    private final VoiceResearchValidator validator;
    private final VoiceResearchRepository repository;
    private final SupabaseStorageClient storageClient;
    private final String bucket;
    private final ConsentService consentService;

    public VoiceResearchService(
            VoiceResearchValidator validator, VoiceResearchRepository repository,
            SupabaseStorageClient storageClient, AppProperties config,
            ConsentService consentService) {
        this.consentService = consentService;
        this.validator = validator;
        this.repository = repository;
        this.storageClient = storageClient;
        this.bucket = config.supabase().voiceResearchBucket();
    }

    /** 현재 약관 버전으로 명시 동의한 보호자 계정만 허용한다(비로그인·선생님·지난 버전 동의는 403) - 요청 검증보다 먼저. 세션 동의에 계정을 남긴다. */
    @Transactional
    public void upload(UploadRequest request, CurrentUser caller) {
        if (caller == null || caller.role() != Role.PARENT) {
            throw ApiException.contractError(
                    ErrorCode.CONSENT_INVALID, "음성 원본은 동의한 보호자 계정에서만 저장해요.", 403);
        }
        UUID accountId = caller.userId();
        VoiceResearchPreference preference = repository.findPreference(accountId);
        if (!isCurrent(preference)) {
            throw ApiException.contractError(
                    ErrorCode.CONSENT_INVALID, "음성 연구 저장에 동의하지 않은 계정이에요.", 403);
        }
        validator.validate(request);
        String deletionTokenHash = DigestUtil.sha256Hex(request.deletionToken());
        VoiceResearchConsent consent = ensureConsent(request, deletionTokenHash, accountId);

        String extension = extensionFor(request.audio().getContentType());
        String objectName = request.consentId() + "/" + request.sampleId() + "." + extension;
        byte[] audioBytes;
        try {
            audioBytes = request.audio().getBytes();
        } catch (Exception readError) {
            throw ApiException.contractError(ErrorCode.INVALID_FORM_DATA, "요청 형식이 올바르지 않아요.");
        }
        if (!storageClient.upload(bucket, objectName, audioBytes, request.audio().getContentType())) {
            throw ApiException.contractError(ErrorCode.STORAGE_FAILED, "녹음을 저장하지 못했어요.", 500);
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
            throw ApiException.contractError(ErrorCode.STORAGE_FAILED, "녹음을 저장하지 못했어요.", 500);
        }
    }

    private VoiceResearchConsent ensureConsent(UploadRequest request, String deletionTokenHash, UUID accountId) {
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
                    .userId(accountId)
                    .build());
        }
        if (!CONSENT_VERSION.equals(consent.getConsentVersion())
                || !DigestUtil.constantTimeEquals(consent.getDeletionTokenHash(), deletionTokenHash)
                || consent.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.contractError(ErrorCode.CONSENT_INVALID, "동의 정보가 올바르지 않아요.", 403);
        }
        // 비로그인으로 시작했다가 로그인한 세션 등 - 토큰으로 소유가 확인된 뒤에만 계정을 남긴다. 이미 다른 계정에
        // 연결된 세션 동의로는 올리지 못한다(공용 기기에서 다른 계정의 동의 아래 녹음이 쌓이면 철회로 지워지지 않는다).
        if (accountId != null && consent.getUserId() == null) {
            consent.setUserId(accountId);
        } else if (accountId != null && !accountId.equals(consent.getUserId())) {
            throw ApiException.contractError(ErrorCode.CONSENT_INVALID, "다른 계정의 동의로는 녹음을 저장할 수 없어요.", 403);
        }
        return consent;
    }

    @Transactional
    public void withdraw(UUID consentId, String deletionToken) {
        VoiceResearchConsent consent = repository.findConsent(consentId);
        if (consent == null || !DigestUtil.constantTimeEquals(consent.getDeletionTokenHash(), DigestUtil.sha256Hex(deletionToken))) {
            throw ApiException.contractError(ErrorCode.CONSENT_INVALID, "동의 정보가 올바르지 않아요.", 403);
        }
        deleteConsentAndSamples(consent);
    }

    @Transactional(readOnly = true)
    public VoiceResearchConsentStatusResponse accountStatus(CurrentUser caller) {
        return statusOf(repository.findPreference(caller.userId()));
    }

    /** 마이페이지에서 다시 동의 - 화면에 보여 준 약관 버전이 서버의 현재 버전과 같아야 한다. */
    @Transactional
    public VoiceResearchConsentStatusResponse grantForAccount(CurrentUser caller, String consentVersion, String source) {
        if (!CONSENT_VERSION.equals(consentVersion)) {
            throw ApiException.contractError(
                    ErrorCode.CONSENT_INVALID, "동의 문구가 바뀌었어요. 화면을 새로 고친 뒤 다시 동의해 주세요.", 409);
        }
        String grantSource = source == null || source.isBlank() ? "MYPAGE" : source;
        if (!GRANT_SOURCES.contains(grantSource)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "동의 출처가 올바르지 않아요.");
        }
        Instant now = Instant.now();
        VoiceResearchPreference preference = preferenceFor(caller.userId(), now);
        preference.setEnabled(true);
        preference.setConsentVersion(CONSENT_VERSION);
        preference.setConsentedAt(now);
        preference.setWithdrawnAt(null);
        preference.setUpdatedAt(now);
        VoiceResearchPreference saved = repository.savePreference(preference);
        recordVoiceRaw(caller, true, grantSource);
        return statusOf(saved);
    }

    private void recordVoiceRaw(CurrentUser caller, boolean agreed, String source) {
        consentService.record(caller, new ConsentRecordRequest(
                source, List.of(new ConsentRecordRequest.Item("VOICE_RAW", agreed, CONSENT_VERSION))));
    }

    /**
     * 마이페이지에서 철회 - 이후 이 계정으로 오는 업로드를 거절하고, 이 계정에 연결된 세션 동의와 그
     * 녹음(Storage 객체 포함)을 모두 지운다. 토큰 기반 withdraw()와 같은 삭제 경로를 쓴다.
     */
    @Transactional
    public VoiceResearchConsentStatusResponse withdrawForAccount(CurrentUser caller) {
        VoiceResearchPreference saved = withdraw(caller.userId());
        recordVoiceRaw(caller, false, "MYPAGE");
        return statusOf(saved);
    }

    /**
     * 회원 탈퇴 시 이 계정에 연결된 녹음을 곧바로 지운다(만료일을 기다리지 않는다). 별도 트랜잭션이라 Storage
     * 삭제가 실패해도 탈퇴는 계속된다 - 호출자(AuthService.deleteAccount)는 app_user 행을 바꾸기 전에 불러야
     * 한다(이 트랜잭션이 그 행을 참조하는 동안 탈퇴 트랜잭션이 행 잠금을 쥐고 있으면 서로 기다린다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void withdrawForDeletedAccount(UUID userId) {
        withdraw(userId);
    }

    private VoiceResearchPreference withdraw(UUID userId) {
        Instant now = Instant.now();
        VoiceResearchPreference preference = preferenceFor(userId, now);
        preference.setEnabled(false);
        preference.setWithdrawnAt(now);
        preference.setUpdatedAt(now);
        VoiceResearchPreference saved = repository.savePreference(preference);
        repository.consentsOfUser(userId).forEach(this::deleteConsentAndSamples);
        return saved;
    }

    private VoiceResearchPreference preferenceFor(UUID userId, Instant now) {
        VoiceResearchPreference existing = repository.findPreference(userId);
        if (existing != null) {
            return existing;
        }
        return VoiceResearchPreference.builder().userId(userId).enabled(DEFAULT_ENABLED).updatedAt(now).build();
    }

    private static VoiceResearchConsentStatusResponse statusOf(VoiceResearchPreference preference) {
        int retentionDays = (int) RETENTION.toDays();
        if (preference == null) {
            return new VoiceResearchConsentStatusResponse(DEFAULT_ENABLED, false, null, null, null, retentionDays);
        }
        // 지난 약관 버전으로 켠 동의는 꺼진 것으로 알린다 - 마이페이지가 새 문구로 다시 묻는다.
        return new VoiceResearchConsentStatusResponse(
                isCurrent(preference), true, preference.getConsentVersion(), preference.getConsentedAt(),
                preference.getWithdrawnAt(), retentionDays);
    }

    /** 켜져 있고 현재 약관 버전으로 동의한 계정만 유효하다 - 버전이 바뀌면 다시 동의받는다. */
    private static boolean isCurrent(VoiceResearchPreference preference) {
        return preference != null && preference.isEnabled() && CONSENT_VERSION.equals(preference.getConsentVersion());
    }

    /** VoiceResearchRetentionScheduler가 호출한다 - 한 번에 최대 200건의 만료 동의를 정리한다. */
    @Transactional
    public int cleanupExpired() {
        List<VoiceResearchConsent> expired = repository.expiredConsents(Instant.now());
        expired.forEach(this::deleteConsentAndSamples);
        return expired.size();
    }

    /**
     * 녹음 객체를 지운 뒤에만 그 기록을 지운다. Storage 삭제가 하나라도 실패하면 동의와 남은 녹음 기록을 두고 만료
     * 시각을 지금으로 당겨, 매일 도는 만료 정리(cleanupExpired)가 다시 시도하게 한다 - 기록을 먼저 지우면 객체가
     * 버킷에 영구히 남는다. 만료된 동의로는 더 이상 업로드할 수 없다.
     */
    private void deleteConsentAndSamples(VoiceResearchConsent consent) {
        boolean allDeleted = true;
        for (VoiceResearchSample sample : repository.samplesForConsent(consent.getId())) {
            if (storageClient.delete(bucket, sample.getStorageObjectName())) {
                repository.deleteSample(sample);
            } else {
                allDeleted = false;
            }
        }
        if (allDeleted) {
            repository.deleteConsent(consent);
            return;
        }
        log.warn("voice-research.delete-retry-scheduled consentId={}", consent.getId());
        consent.setExpiresAt(Instant.now());
        repository.saveConsent(consent);
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
