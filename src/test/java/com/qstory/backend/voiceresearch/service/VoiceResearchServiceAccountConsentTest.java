package com.qstory.backend.voiceresearch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.ConsentRecordRequest;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.service.ConsentService;
import com.qstory.backend.voiceresearch.dto.UploadRequest;
import com.qstory.backend.voiceresearch.dto.VoiceResearchConsentStatusResponse;
import com.qstory.backend.voiceresearch.entity.VoiceResearchConsent;
import com.qstory.backend.voiceresearch.entity.VoiceResearchPreference;
import com.qstory.backend.voiceresearch.entity.VoiceResearchSample;
import com.qstory.backend.voiceresearch.repository.VoiceResearchRepository;
import com.qstory.backend.voiceresearch.util.VoiceResearchValidator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 마이페이지 계정 단위 음성 연구 동의: 기본값, 다시 동의(약관 버전 확인), 철회(계정에 연결된 녹음 삭제),
 * 그리고 동의를 끈 보호자 계정의 업로드 거절과 동의한 계정의 세션 동의 연결.
 */
class VoiceResearchServiceAccountConsentTest {

    private final VoiceResearchValidator validator = mock(VoiceResearchValidator.class);
    private final VoiceResearchRepository repository = mock(VoiceResearchRepository.class);
    private final SupabaseStorageClient storageClient = mock(SupabaseStorageClient.class);
    private final ConsentService consentService = mock(ConsentService.class);
    private final VoiceResearchService service;

    private final UUID parentId = UUID.randomUUID();
    private final CurrentUser parent = new CurrentUser(parentId, Role.PARENT, null);

    VoiceResearchServiceAccountConsentTest() {
        AppProperties config = mock(AppProperties.class);
        when(config.supabase()).thenReturn(
                new AppProperties.Supabase("https://example.supabase.co", "key", "voice-bucket", null, null, null, null));
        service = new VoiceResearchService(validator, repository, storageClient, config, consentService);
        when(repository.savePreference(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveConsent(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void parentWithoutAnyChoiceGetsTheServerDefault() {
        VoiceResearchConsentStatusResponse status = service.accountStatus(parent);

        assertFalse(VoiceResearchService.DEFAULT_ENABLED);
        assertFalse(status.enabled());
        assertFalse(status.explicit());
        assertNull(status.consentedAt());
        assertEquals(365, status.retentionDays());
    }

    @Test
    void grantRecordsTheCurrentVersionAndTime() {
        VoiceResearchConsentStatusResponse status =
                service.grantForAccount(parent, VoiceResearchService.CONSENT_VERSION, null);

        assertTrue(status.enabled());
        assertTrue(status.explicit());
        assertEquals(VoiceResearchService.CONSENT_VERSION, status.consentVersion());
        assertNotNull(status.consentedAt());
        assertNull(status.withdrawnAt());
    }

    @Test
    void grantWithAStaleVersionIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.grantForAccount(parent, "voice-research-v1", null));

        assertEquals(409, error.statusCode());
        verify(repository, never()).savePreference(any());
    }

    @Test
    void regrantAfterWithdrawalClearsTheWithdrawalTime() {
        when(repository.findPreference(parentId)).thenReturn(VoiceResearchPreference.builder()
                .userId(parentId).enabled(false).withdrawnAt(Instant.now()).updatedAt(Instant.now()).build());

        VoiceResearchConsentStatusResponse status =
                service.grantForAccount(parent, VoiceResearchService.CONSENT_VERSION, null);

        assertTrue(status.enabled());
        assertNull(status.withdrawnAt());
    }

    @Test
    void withdrawTurnsConsentOffAndDeletesEveryLinkedRecording() {
        VoiceResearchConsent first = consent(parentId);
        VoiceResearchConsent second = consent(parentId);
        when(repository.consentsOfUser(parentId)).thenReturn(List.of(first, second));
        when(repository.samplesForConsent(first.getId())).thenReturn(List.of(sample(first, "a.webm"), sample(first, "b.webm")));
        when(repository.samplesForConsent(second.getId())).thenReturn(List.of(sample(second, "c.m4a")));
        when(storageClient.delete(anyString(), anyString())).thenReturn(true);

        VoiceResearchConsentStatusResponse status = service.withdrawForAccount(parent);

        assertFalse(status.enabled());
        assertTrue(status.explicit());
        assertNotNull(status.withdrawnAt());
        verify(storageClient).delete("voice-bucket", "a.webm");
        verify(storageClient).delete("voice-bucket", "b.webm");
        verify(storageClient).delete("voice-bucket", "c.m4a");
        verify(repository).deleteConsent(first);
        verify(repository).deleteConsent(second);
    }

    @Test
    void failedStorageDeleteKeepsTheRecordsAndSchedulesARetry() {
        VoiceResearchConsent consent = consent(parentId);
        VoiceResearchSample kept = sample(consent, "stuck.webm");
        VoiceResearchSample gone = sample(consent, "ok.webm");
        when(repository.consentsOfUser(parentId)).thenReturn(List.of(consent));
        when(repository.samplesForConsent(consent.getId())).thenReturn(List.of(kept, gone));
        when(storageClient.delete("voice-bucket", "stuck.webm")).thenReturn(false);
        when(storageClient.delete("voice-bucket", "ok.webm")).thenReturn(true);

        service.withdrawForAccount(parent);

        verify(repository).deleteSample(gone);
        verify(repository, never()).deleteSample(kept);
        verify(repository, never()).deleteConsent(consent);
        verify(repository).saveConsent(consent);
        assertFalse(consent.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void sessionConsentOwnedByAnotherAccountIsRejected() {
        UploadRequest request = uploadRequest();
        VoiceResearchConsent othersConsent = VoiceResearchConsent.builder()
                .id(request.consentId())
                .deletionTokenHash(DigestUtil.sha256Hex(request.deletionToken()))
                .consentVersion(VoiceResearchService.CONSENT_VERSION)
                .consentedAt(request.consentedAt())
                .expiresAt(Instant.now().plus(90, ChronoUnit.DAYS))
                .createdAt(Instant.now())
                .userId(java.util.UUID.randomUUID())
                .build();
        when(repository.findPreference(parentId)).thenReturn(VoiceResearchPreference.builder()
                .userId(parentId).enabled(true).updatedAt(Instant.now()).build());
        when(repository.findConsent(eq(request.consentId()))).thenReturn(othersConsent);

        ApiException error = assertThrows(ApiException.class, () -> service.upload(request, parent));

        assertEquals(403, error.statusCode());
        verify(storageClient, never()).upload(anyString(), anyString(), any(), anyString());
    }

    @Test
    void uploadFromAParentWhoWithdrewIsRejectedBeforeStoring() {
        when(repository.findPreference(parentId)).thenReturn(VoiceResearchPreference.builder()
                .userId(parentId).enabled(false).updatedAt(Instant.now()).build());

        ApiException error = assertThrows(ApiException.class, () -> service.upload(uploadRequest(), parent));

        assertEquals(ErrorCode.CONSENT_INVALID, error.code());
        assertEquals(403, error.statusCode());
        verify(storageClient, never()).upload(anyString(), anyString(), any(), anyString());
    }

    @Test
    void grantRecordsVoiceRawAgreedRowWithGivenSourceOrMypage() {
        service.grantForAccount(parent, VoiceResearchService.CONSENT_VERSION, "ONBOARDING");
        service.grantForAccount(parent, VoiceResearchService.CONSENT_VERSION, null);

        ArgumentCaptor<ConsentRecordRequest> req = ArgumentCaptor.forClass(ConsentRecordRequest.class);
        verify(consentService, org.mockito.Mockito.times(2)).record(eq(parent), req.capture());
        assertEquals("ONBOARDING", req.getAllValues().get(0).source());
        assertEquals("MYPAGE", req.getAllValues().get(1).source());
        ConsentRecordRequest.Item item = req.getAllValues().get(0).items().get(0);
        assertEquals("VOICE_RAW", item.type());
        assertTrue(item.agreed());
    }

    @Test
    void withdrawRecordsVoiceRawDisagreedRow() {
        service.withdrawForAccount(parent);

        ArgumentCaptor<ConsentRecordRequest> req = ArgumentCaptor.forClass(ConsentRecordRequest.class);
        verify(consentService).record(eq(parent), req.capture());
        assertEquals("MYPAGE", req.getValue().source());
        assertEquals("VOICE_RAW", req.getValue().items().get(0).type());
        assertFalse(req.getValue().items().get(0).agreed());
    }

    @Test
    void parentWithNoPreferenceRowIsRejected() {
        ApiException error = assertThrows(ApiException.class, () -> service.upload(uploadRequest(), parent));

        assertEquals(403, error.statusCode());
        verify(storageClient, never()).upload(anyString(), anyString(), any(), anyString());
    }

    @Test
    void tutorUploadIsRejected() {
        CurrentUser tutor = new CurrentUser(UUID.randomUUID(), Role.TUTOR, null);

        ApiException error = assertThrows(ApiException.class, () -> service.upload(uploadRequest(), tutor));

        assertEquals(403, error.statusCode());
        verify(storageClient, never()).upload(anyString(), anyString(), any(), anyString());
    }

    @Test
    void uploadFromAConsentingParentLinksTheSessionConsentToTheAccount() {
        when(repository.findPreference(parentId)).thenReturn(VoiceResearchPreference.builder()
                .userId(parentId).enabled(true).updatedAt(Instant.now()).build());
        when(storageClient.upload(anyString(), anyString(), any(), anyString())).thenReturn(true);

        service.upload(uploadRequest(), parent);

        ArgumentCaptor<VoiceResearchConsent> saved = ArgumentCaptor.forClass(VoiceResearchConsent.class);
        verify(repository).saveConsent(saved.capture());
        assertEquals(parentId, saved.getValue().getUserId());
    }

    @Test
    void anonymousUploadIsRejected() {
        ApiException error = assertThrows(ApiException.class, () -> service.upload(uploadRequest(), null));

        assertEquals(403, error.statusCode());
        verify(storageClient, never()).upload(anyString(), anyString(), any(), anyString());
        verify(repository, never()).saveConsent(any());
    }

    @Test
    void existingAnonymousSessionConsentIsLinkedOnceTheTokenMatches() {
        when(repository.findPreference(parentId)).thenReturn(VoiceResearchPreference.builder()
                .userId(parentId).enabled(true).updatedAt(Instant.now()).build());
        UploadRequest request = uploadRequest();
        VoiceResearchConsent anonymous = VoiceResearchConsent.builder()
                .id(request.consentId())
                .deletionTokenHash(DigestUtil.sha256Hex(request.deletionToken()))
                .consentVersion(VoiceResearchService.CONSENT_VERSION)
                .consentedAt(request.consentedAt())
                .expiresAt(Instant.now().plus(90, ChronoUnit.DAYS))
                .createdAt(Instant.now())
                .build();
        when(repository.findConsent(eq(request.consentId()))).thenReturn(anonymous);
        when(storageClient.upload(anyString(), anyString(), any(), anyString())).thenReturn(true);

        service.upload(request, parent);

        assertEquals(parentId, anonymous.getUserId());
    }

    private static VoiceResearchConsent consent(UUID userId) {
        return VoiceResearchConsent.builder().id(UUID.randomUUID()).userId(userId).build();
    }

    private static VoiceResearchSample sample(VoiceResearchConsent consent, String objectName) {
        return VoiceResearchSample.builder().id(UUID.randomUUID()).consent(consent).storageObjectName(objectName).build();
    }

    private static UploadRequest uploadRequest() {
        MockMultipartFile audio = new MockMultipartFile("audio", "question.webm", "audio/webm", new byte[] {1, 2, 3});
        return new UploadRequest(
                UUID.randomUUID(), UUID.randomUUID().toString(), Instant.now(), UUID.randomUUID(), "story",
                "scene-1", "anchor-1", "초안", "확인한 질문", 1, 1_000, audio, null, null, null);
    }
}
