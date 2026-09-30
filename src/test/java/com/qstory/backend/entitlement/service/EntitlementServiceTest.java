package com.qstory.backend.entitlement.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.SubscriptionStatus;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 학부모의 기관 이용권은 계정 소속이 아니라 아이가 들어간 기관 반(학생 명단)에서 계산한다. */
class EntitlementServiceTest {

    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final TutorStudentRepository tutorStudentRepository = mock(TutorStudentRepository.class);
    private final EntitlementService service =
            new EntitlementService(organizationRepository, appUserRepository, tutorStudentRepository);

    private final StoryManifest paidStory = mock(StoryManifest.class);
    private final UUID parentId = UUID.randomUUID();
    private final CurrentUser parent = new CurrentUser(parentId, Role.PARENT, null);

    EntitlementServiceTest() {
        when(paidStory.requiresEntitlement()).thenReturn(true);
        when(appUserRepository.findById(any())).thenReturn(Optional.of(AppUser.builder().role(Role.PARENT).build()));
    }

    private static Organization organization(SubscriptionStatus status, Instant expiresAt) {
        return Organization.builder().subscriptionStatus(status).subscriptionExpiresAt(expiresAt).build();
    }

    @Test
    void parentWhoseChildIsInAnActiveOrganizationClassHasAccess() {
        Organization active = organization(SubscriptionStatus.ACTIVE, Instant.now().plus(10, ChronoUnit.DAYS));
        when(tutorStudentRepository.findOrganizationsOfParent(parentId)).thenReturn(List.of(active));
        assertDoesNotThrow(() -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void oneActiveOrganizationIsEnoughWhenAnotherHasExpired() {
        Organization expired = organization(SubscriptionStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS));
        Organization active = organization(SubscriptionStatus.ACTIVE, Instant.now().plus(10, ChronoUnit.DAYS));
        when(tutorStudentRepository.findOrganizationsOfParent(parentId)).thenReturn(List.of(expired, active));
        assertDoesNotThrow(() -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void expiredOrUnsubscribedOrganizationDoesNotGrantAccess() {
        Organization expired = organization(SubscriptionStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS));
        when(tutorStudentRepository.findOrganizationsOfParent(parentId)).thenReturn(List.of(expired));
        assertThrows(ApiException.class, () -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void parentWithNoClassAndNoPersonalSubscriptionHasNoAccess() {
        when(tutorStudentRepository.findOrganizationsOfParent(parentId)).thenReturn(List.of());
        assertThrows(ApiException.class, () -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void staleOrgIdClaimOnAParentTokenGrantsNothing() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(organization(SubscriptionStatus.ACTIVE, Instant.now().plus(10, ChronoUnit.DAYS))));
        when(tutorStudentRepository.findOrganizationsOfParent(parentId)).thenReturn(List.of());
        CurrentUser staleToken = new CurrentUser(parentId, Role.PARENT, orgId);
        assertThrows(ApiException.class, () -> service.assertAccessible(paidStory, staleToken));
    }

    @Test
    void directorKeepsAccessThroughTheirOrganization() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(organization(SubscriptionStatus.ACTIVE, Instant.now().plus(10, ChronoUnit.DAYS))));
        CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, orgId);
        assertDoesNotThrow(() -> service.assertAccessible(paidStory, director));
    }
}
