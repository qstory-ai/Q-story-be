package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import com.qstory.backend.institution.InstitutionKind;
import com.qstory.backend.institution.entity.PublicInstitution;
import com.qstory.backend.institution.repository.PublicInstitutionRepository;
import com.qstory.backend.org.dto.CreateOrganizationRequest;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/** 원장 기관 등록을 공공 기관 목록에서 고르는 경우(083) - 그 이름으로 등록, 이미 등록된 곳은 409, 직접 입력은 그대로. */
class OrganizationServicePublicInstitutionTest {

    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final PublicInstitutionRepository publicInstitutionRepository = mock(PublicInstitutionRepository.class);
    private final OrganizationService service = new OrganizationService(
            organizationRepository, userRepository, mock(JwtService.class), mock(UserSummaryFactory.class),
            publicInstitutionRepository, mock(ConsentService.class));

    private static final String ACK = "2026-10-v2";

    private final AppUser director = AppUser.builder().id(UUID.randomUUID()).role(Role.DIRECTOR).build();
    private final CurrentUser caller = new CurrentUser(director.getId(), Role.DIRECTOR, null);
    private final PublicInstitution kinder = PublicInstitution.builder()
            .id(UUID.randomUUID()).kind(InstitutionKind.KINDERGARTEN).name("가온유치원").status("ACTIVE").build();

    @BeforeEach
    void setUp() {
        when(userRepository.findById(director.getId())).thenReturn(Optional.of(director));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            if (organization.getId() == null) organization.setId(UUID.randomUUID());
            return organization;
        });
        when(publicInstitutionRepository.findById(kinder.getId())).thenReturn(Optional.of(kinder));
    }

    @Test
    void registersWithPublicInstitutionName() {
        service.create(caller, new CreateOrganizationRequest("아무 이름", true, ACK, kinder.getId()));

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).saveAndFlush(saved.capture());
        assertEquals("가온유치원", saved.getValue().getName());
        assertEquals(kinder.getId(), saved.getValue().getPublicInstitutionId());
        assertEquals(ACK, saved.getValue().getGuardianConsentAckVersion(), "목록에서 골라도 보호자 동의 확인이 남는다");
    }

    @Test
    void manualEntryStillWorks() {
        service.create(caller, new CreateOrganizationRequest(" 별빛 공부방 ", true, ACK, null));

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).saveAndFlush(saved.capture());
        assertEquals("별빛 공부방", saved.getValue().getName());
        assertEquals(null, saved.getValue().getPublicInstitutionId());
    }

    @Test
    void alreadyRegisteredInstitutionIsRejected() {
        when(organizationRepository.existsByPublicInstitutionId(kinder.getId())).thenReturn(true);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(caller, new CreateOrganizationRequest(null, true, ACK, kinder.getId())));
        assertEquals(ErrorCode.PUBLIC_INSTITUTION_ALREADY_REGISTERED, error.code());
        verify(organizationRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentRegistrationHitsUniqueIndexAndGetsSame409() {
        doThrow(new DataIntegrityViolationException("dup")).when(organizationRepository).saveAndFlush(any());

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(caller, new CreateOrganizationRequest(null, true, ACK, kinder.getId())));
        assertEquals(ErrorCode.PUBLIC_INSTITUTION_ALREADY_REGISTERED, error.code());
    }

    @Test
    void closedInstitutionIsNotRegistrable() {
        PublicInstitution closed = PublicInstitution.builder()
                .id(UUID.randomUUID()).kind(InstitutionKind.DAYCARE).name("폐지어린이집").status("CLOSED").build();
        when(publicInstitutionRepository.findById(closed.getId())).thenReturn(Optional.of(closed));

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(caller, new CreateOrganizationRequest(null, true, ACK, closed.getId())));
        assertEquals(ErrorCode.NOT_FOUND, error.code());
    }

    @Test
    void linkExistingOrganizationOnlyForOwnOrganization() {
        Organization mine = Organization.builder().id(UUID.randomUUID()).name("우리 유치원").build();
        when(organizationRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        CurrentUser owner = new CurrentUser(director.getId(), Role.DIRECTOR, mine.getId());

        var profile = service.linkPublicInstitution(owner, mine.getId(), kinder.getId());
        assertEquals("우리 유치원", profile.name(), "연결해도 원장이 정한 이름은 그대로");
        assertEquals(kinder.getId(), profile.publicInstitution().id());

        CurrentUser other = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID());
        assertThrows(ApiException.class, () -> service.linkPublicInstitution(other, mine.getId(), kinder.getId()));
    }
}
