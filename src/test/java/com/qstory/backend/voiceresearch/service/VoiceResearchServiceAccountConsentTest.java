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
import com.qstory.backend.identity.security.CurrentUser;
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
    private final VoiceResearchService service;

    private final UUID parentId = UUID.randomUUID();
    private final CurrentUser parent = new CurrentUser(parentId, Role.PARENT, null);

    VoiceResearchServiceAccountConsentTest() {
        AppProperties config = mock(AppProperties.class);
        when(config.supabase()).thenReturn(
                new AppProperties.Supabase("https://example.supabase.co", "key", "voice-bucket", null, null, null, null));
        service = new VoiceResearchService(validator, repository, storageClient, config);
        when(repository.savePreference(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveConsent(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void parentWithoutAnyChoiceGetsTheServerDefault() {
        VoiceResearchConsentStatusResponse status = service.accountStatus(parent);

        assertEquals(VoiceResearchService.DEFAULT_ENABLED, status.enabled());
        assertFalse(status.explicit());
        assertNull(status.consentedAt());
        assertEquals(90, status.retentionDays());
    }

    @Test
    void grantRecordsTheCurrentVersionAndTime() {
        VoiceResearchConsentStatusResponse status =
                service.grantForAccount(parent, VoiceResearchService.CONSENT_VERSION);

        assertTrue(status.enabled());
        assertTrue(status.explicit());
        assertEquals(VoiceResearchService.CONSENT_VERSION, status.consentVersion());
        assertNotNull(status.consentedAt());
        assertNull(status.withdrawnAt());
    }

    @Test
    void grantWithAStaleVersionIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.grantForAccount(parent, "voice-research-v1"));

        assertEquals(409, error.statusCode());
        verify(repository, never()).savePreference(any());
    }

    @Test
    void regrantAfterWithdrawalClearsTheWithdrawalTime() {
        when(repository.findPreference(parentId)).thenReturn(VoiceResearchPreference.builder()
                .userId(parentId).enabled(false).withdrawnAt(Instant.now()).updatedAt(Instant.now()).build());

        VoiceResearchConsentStatusResponse status =
                service.grantForAccount(parent, VoiceResearchService.CONSENT_VERSION);

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
    void uploadFromAParentWhoWithdrewIsRejectedBeforeStoring() {
        when(repository.findPreference(parentId)).thenReturn(VoiceResearchPreference.builder()
                .userId(parentId).enabled(false).updatedAt(Instant.now()).build());

        ApiException error = assertThrows(ApiException.class, () -> service.upload(uploadRequest(), parent));

        assertEquals(ErrorCode.CONSENT_INVALID, error.code());
        assertEquals(403, error.statusCode());
        verify(storageClient, never()).upload(anyString(), anyString(), any(), anyString());
    }

    @Test
    void uploadFromAConsentingParentLinksTheSessionConsentToTheAccount() {
        when(storageClient.upload(anyString(), anyString(), any(), anyString())).thenReturn(true);

        service.upload(uploadRequest(), parent);

        ArgumentCaptor<VoiceResearchConsent> saved = ArgumentCaptor.forClass(VoiceResearchConsent.class);
        verify(repository).saveConsent(saved.capture());
        assertEquals(parentId, saved.getValue().getUserId());
    }

    @Test
    void anonymousUploadStaysUnlinkedAndIgnoresAccountConsent() {
        when(storageClient.upload(anyString(), anyString(), any(), anyString())).thenReturn(true);

        service.upload(uploadRequest(), null);

        ArgumentCaptor<VoiceResearchConsent> saved = ArgumentCaptor.forClass(VoiceResearchConsent.class);
        verify(repository).saveConsent(saved.capture());
        assertNull(saved.getValue().getUserId());
        verify(repository, never()).findPreference(any());
    }

    @Test
    void existingAnonymousSessionConsentIsLinkedOnceTheTokenMatches() {
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
