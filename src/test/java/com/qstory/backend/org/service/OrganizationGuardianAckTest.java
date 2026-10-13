package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.ConsentService;
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.org.dto.CreateOrganizationRequest;
import com.qstory.backend.org.dto.GuardianConsentAckRequest;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A5: 기관은 "원아 보호자 동의를 받아 이용한다"를 확인해야 만들 수 있고, 확인은 기관 행과 원장 동의 이력에 남는다. */
class OrganizationGuardianAckTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ConsentService consents = mock(ConsentService.class);
    private final OrganizationService service = new OrganizationService(
            organizations, users, mock(JwtService.class), mock(UserSummaryFactory.class), consents);
    private final UUID directorId = UUID.randomUUID();
    private final CurrentUser caller = new CurrentUser(directorId, Role.DIRECTOR, null);

    @Test
    void createWithoutAcknowledgementIsRejected() {
        when(users.findById(directorId)).thenReturn(Optional.of(AppUser.builder().id(directorId).role(Role.DIRECTOR).build()));
        for (CreateOrganizationRequest bad : new CreateOrganizationRequest[] {
                new CreateOrganizationRequest("무지개 유치원", null, null),
                new CreateOrganizationRequest("무지개 유치원", false, "2026-10-v2"),
                new CreateOrganizationRequest("무지개 유치원", true, " ")}) {
            ApiException e = assertThrows(ApiException.class, () -> service.create(caller, bad));
            assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
        }
        verify(organizations, never()).save(any());
    }

    @Test
    void createRecordsAcknowledgementOnOrganization() {
        AppUser director = AppUser.builder().id(directorId).role(Role.DIRECTOR).build();
        when(users.findById(directorId)).thenReturn(Optional.of(director));
        when(organizations.save(any())).thenAnswer(i -> {
            Organization org = i.getArgument(0);
            org.setId(UUID.randomUUID());
            return org;
        });
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));

        service.create(caller, new CreateOrganizationRequest("무지개 유치원", true, "2026-10-v2"));

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizations).save(saved.capture());
        assertNotNull(saved.getValue().getGuardianConsentAckAt());
        assertEquals("2026-10-v2", saved.getValue().getGuardianConsentAckVersion());
        assertEquals(directorId, saved.getValue().getGuardianConsentAckBy());
        verify(consents).recordOrganizationGuardianAck(directorId, "2026-10-v2", "ORGANIZATION_CREATE");
    }

    @Test
    void existingOrganizationCanAcknowledgeOnlyWhenOwned() {
        UUID orgId = UUID.randomUUID();
        Organization org = Organization.builder().id(orgId).name("햇살").build();
        when(organizations.findById(orgId)).thenReturn(Optional.of(org));

        CurrentUser owner = new CurrentUser(directorId, Role.DIRECTOR, orgId);
        service.acknowledgeGuardianConsent(owner, orgId, new GuardianConsentAckRequest("2026-10-v2"));
        assertNotNull(org.getGuardianConsentAckAt());
        assertEquals(directorId, org.getGuardianConsentAckBy());
        verify(consents).recordOrganizationGuardianAck(directorId, "2026-10-v2", "RECONSENT");

        CurrentUser other = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID());
        ApiException e = assertThrows(ApiException.class,
                () -> service.acknowledgeGuardianConsent(other, orgId, new GuardianConsentAckRequest("2026-10-v2")));
        assertEquals(403, e.statusCode());
    }
}
