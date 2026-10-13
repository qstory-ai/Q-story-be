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
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.parent.notification.entity.NotificationSettings;
import com.qstory.backend.parent.notification.repository.NotificationSettingsRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConsentServiceTest {

    private final UserConsentRepository consents = mock(UserConsentRepository.class);
    private final NotificationSettingsRepository settings = mock(NotificationSettingsRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final ConsentService service = new ConsentService(consents, settings, organizations);
    private final AppUser user = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).build();

    private static ConsentPayload payload(boolean terms, boolean privacy, boolean marketing) {
        return new ConsentPayload("2026-10-v2", terms, privacy, marketing, true, true);
    }

    @Test
    void missingOrIncompleteConsentIsRejected() {
        for (ConsentPayload bad : new ConsentPayload[] {
                null, payload(false, true, true), payload(true, false, true),
                new ConsentPayload("  ", true, true, false, true, true),
                new ConsentPayload(null, true, true, false, true, true)}) {
            ApiException e = assertThrows(ApiException.class, () -> service.requireSignupConsents(bad, Role.PARENT));
            assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
            assertEquals(400, e.statusCode());
        }
        service.requireSignupConsents(payload(true, true, false), Role.PARENT);
    }

    /** A5·A9: 국외 이전은 모든 역할, 법정대리인 확인은 보호자만 필수. 예전 화면(두 값 없음 = false)은 400. */
    @Test
    void guardianIsRequiredForParentsAndOverseasTransferForEveryone() {
        ConsentPayload legacy = new ConsentPayload("2026-10-v1", true, true, false, false, false);
        ConsentPayload noGuardian = new ConsentPayload("2026-10-v2", true, true, false, false, true);
        ConsentPayload noOverseas = new ConsentPayload("2026-10-v2", true, true, false, true, false);
        for (Role role : new Role[] {Role.PARENT, Role.TUTOR, Role.DIRECTOR}) {
            ApiException e = assertThrows(ApiException.class, () -> service.requireSignupConsents(legacy, role));
            assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
            assertThrows(ApiException.class, () -> service.requireSignupConsents(noOverseas, role));
        }
        assertThrows(ApiException.class, () -> service.requireSignupConsents(noGuardian, Role.PARENT));
        service.requireSignupConsents(noGuardian, Role.TUTOR);
        service.requireSignupConsents(noGuardian, Role.DIRECTOR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void signupStoresRowsAndMarketingSetting() {
        service.recordSignup(user, payload(true, true, true), "SIGNUP");

        ArgumentCaptor<List<UserConsent>> rows = ArgumentCaptor.forClass(List.class);
        verify(consents).saveAll(rows.capture());
        assertTrue(rows.getValue().stream().allMatch(r -> r.getSource().equals("SIGNUP")
                && r.getVersion().equals("2026-10-v2") && r.getUserId().equals(user.getId())));
        assertEquals(List.of("TERMS", "PRIVACY", "MARKETING", "OVERSEAS_TRANSFER", "GUARDIAN"),
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
    @SuppressWarnings("unchecked")
    void teacherSignupHasNoGuardianRow() {
        AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).build();
        service.recordSignup(tutor, new ConsentPayload("2026-10-v2", true, true, false, false, true), "SIGNUP");
        ArgumentCaptor<List<UserConsent>> rows = ArgumentCaptor.forClass(List.class);
        verify(consents).saveAll(rows.capture());
        assertEquals(List.of("TERMS", "PRIVACY", "MARKETING", "OVERSEAS_TRANSFER"),
                rows.getValue().stream().map(UserConsent::getConsentType).toList());
    }

    @Test
    void pendingConsentsForAccountsBeforeV2() {
        // 기록 없음 - 보호자는 두 개, 선생님은 국외 이전만, STAFF는 없음.
        assertEquals(List.of("GUARDIAN", "OVERSEAS_TRANSFER"), service.pendingConsents(user));
        AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).build();
        assertEquals(List.of("OVERSEAS_TRANSFER"), service.pendingConsents(tutor));
        AppUser staff = AppUser.builder().id(UUID.randomUUID()).role(Role.STAFF).build();
        assertEquals(List.of(), service.pendingConsents(staff));
    }

    @Test
    void latestRowDecidesPending() {
        when(consents.findByUserIdAndConsentTypeInOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(
                consent("GUARDIAN", true), consent("OVERSEAS_TRANSFER", false), consent("OVERSEAS_TRANSFER", true)));
        assertEquals(List.of("OVERSEAS_TRANSFER"), service.pendingConsents(user));
    }

    @Test
    void directorWithUnacknowledgedOrganizationIsPending() {
        Organization org = Organization.builder().id(UUID.randomUUID()).build();
        AppUser director = AppUser.builder().id(UUID.randomUUID()).role(Role.DIRECTOR).organization(org).build();
        when(consents.findByUserIdAndConsentTypeInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(consent("OVERSEAS_TRANSFER", true)));
        assertEquals(List.of("ORG_GUARDIAN_ACK"), service.pendingConsents(director));
        when(organizations.existsByIdAndGuardianConsentAckAtIsNotNull(org.getId())).thenReturn(true);
        assertEquals(List.of(), service.pendingConsents(director));
        // 기관이 아직 없는 원장은 기관을 만들 때 확인한다.
        AppUser noOrg = AppUser.builder().id(UUID.randomUUID()).role(Role.DIRECTOR).build();
        assertEquals(List.of(), service.pendingConsents(noOrg));
    }

    @Test
    void reconsentSourceAndNewTypesAreAccepted() {
        service.record(new CurrentUser(user.getId(), Role.PARENT, null), new ConsentRecordRequest("RECONSENT", List.of(
                new ConsentRecordRequest.Item("GUARDIAN", true, "2026-10-v2"),
                new ConsentRecordRequest.Item("OVERSEAS_TRANSFER", true, "2026-10-v2"))));
        verify(consents).saveAll(any());
    }

    private static UserConsent consent(String type, boolean agreed) {
        return UserConsent.builder().consentType(type).agreed(agreed).build();
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
                List.of(new ConsentRecordRequest.Item("CHILD_REPORT_SCOPE", true, "2026-10-v2"))));
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
