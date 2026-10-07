package com.qstory.backend.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.ConsentPayload;
import com.qstory.backend.identity.dto.ConsentRecordRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.entity.UserConsent;
import com.qstory.backend.identity.repository.UserConsentRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.parent.notification.entity.NotificationSettings;
import com.qstory.backend.parent.notification.repository.NotificationSettingsRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConsentServiceTest {

    private final UserConsentRepository consents = mock(UserConsentRepository.class);
    private final NotificationSettingsRepository settings = mock(NotificationSettingsRepository.class);
    private final ConsentService service = new ConsentService(consents, settings);
    private final AppUser user = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).build();

    private static ConsentPayload payload(boolean terms, boolean privacy, boolean marketing) {
        return new ConsentPayload("2026-10-v1", terms, privacy, marketing);
    }

    @Test
    void missingOrIncompleteConsentIsRejected() {
        for (ConsentPayload bad : new ConsentPayload[] {
                null, payload(false, true, true), payload(true, false, true),
                new ConsentPayload("  ", true, true, false), new ConsentPayload(null, true, true, false)}) {
            ApiException e = assertThrows(ApiException.class, () -> service.requireSignupConsents(bad));
            assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
            assertEquals(400, e.statusCode());
        }
        service.requireSignupConsents(payload(true, true, false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void signupStoresThreeRowsAndMarketingSetting() {
        service.recordSignup(user, payload(true, true, true), "SIGNUP");

        ArgumentCaptor<List<UserConsent>> rows = ArgumentCaptor.forClass(List.class);
        verify(consents).saveAll(rows.capture());
        assertEquals(3, rows.getValue().size());
        assertTrue(rows.getValue().stream().allMatch(r -> r.getSource().equals("SIGNUP")
                && r.getVersion().equals("2026-10-v1") && r.getUserId().equals(user.getId())));
        assertEquals(List.of("TERMS", "PRIVACY", "MARKETING"),
                rows.getValue().stream().map(UserConsent::getConsentType).toList());
        ArgumentCaptor<NotificationSettings> saved = ArgumentCaptor.forClass(NotificationSettings.class);
        verify(settings).save(saved.capture());
        assertTrue(saved.getValue().isMarketingEnabled());
    }

    @Test
    @SuppressWarnings("unchecked")
    void marketingFalseIsStoredAsDisagreed() {
        service.recordSignup(user, payload(true, true, false), "CLASS_JOIN_SIGNUP");
        ArgumentCaptor<List<UserConsent>> rows = ArgumentCaptor.forClass(List.class);
        verify(consents).saveAll(rows.capture());
        assertFalse(rows.getValue().get(2).isAgreed());
        ArgumentCaptor<NotificationSettings> saved = ArgumentCaptor.forClass(NotificationSettings.class);
        verify(settings).save(saved.capture());
        assertFalse(saved.getValue().isMarketingEnabled());
    }

    @Test
    void existingSettingsRowIsUpdatedNotReplaced() {
        NotificationSettings existing = NotificationSettings.builder().user(user).marketingEnabled(false).build();
        when(settings.findById(user.getId())).thenReturn(java.util.Optional.of(existing));
        service.recordSignup(user, payload(true, true, true), "SIGNUP");
        assertTrue(existing.isMarketingEnabled());
        verify(settings).save(existing);
    }

    @Test
    void notificationSettingsEntityDefaultsMarketingOff() {
        assertFalse(NotificationSettings.builder().build().isMarketingEnabled());
    }

    @Test
    @SuppressWarnings("unchecked")
    void meConsentsAreSaved() {
        service.record(new CurrentUser(user.getId(), Role.PARENT, null), new ConsentRecordRequest("ONBOARDING",
                List.of(new ConsentRecordRequest.Item("CHILD_REPORT_SCOPE", true, "2026-10-v1"))));
        ArgumentCaptor<List<UserConsent>> rows = ArgumentCaptor.forClass(List.class);
        verify(consents).saveAll(rows.capture());
        assertEquals("CHILD_REPORT_SCOPE", rows.getValue().get(0).getConsentType());
        assertEquals("ONBOARDING", rows.getValue().get(0).getSource());
        assertTrue(rows.getValue().get(0).isAgreed());
    }

    @Test
    void meConsentsRejectUnknownTypeOrSource() {
        CurrentUser caller = new CurrentUser(user.getId(), Role.PARENT, null);
        assertThrows(ApiException.class, () -> service.record(caller, new ConsentRecordRequest("ONBOARDING",
                List.of(new ConsentRecordRequest.Item("NOPE", true, "v")))));
        assertThrows(ApiException.class, () -> service.record(caller, new ConsentRecordRequest("NOPE",
                List.of(new ConsentRecordRequest.Item("VOICE_RAW", true, "v")))));
        assertThrows(ApiException.class, () -> service.record(caller, new ConsentRecordRequest("MYPAGE", List.of())));
        verify(consents, never()).saveAll(any());
    }
}
