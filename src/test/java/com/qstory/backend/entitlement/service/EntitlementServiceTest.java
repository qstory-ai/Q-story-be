package com.qstory.backend.entitlement.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.config.BetaProperties;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.SubscriptionStatus;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.tutor.repository.ParentClassSeat;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 기관 이용권이 열리는 조건: 원장은 자기 기관, 선생님은 소속 기관, 학부모는 아이가 들어간 기관 반의 기관이 구독 중이고
 * 아이가 결제한 인원 안에 들 때다. 학부모의 근거는 계정 소속이 아니라 학생 명단이다.
 */
class EntitlementServiceTest {

    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final TutorStudentRepository tutorStudentRepository = mock(TutorStudentRepository.class);
    private final OrganizationTutorRepository organizationTutorRepository = mock(OrganizationTutorRepository.class);
    private final EntitlementService service = new EntitlementService(
            organizationRepository, appUserRepository, tutorStudentRepository, organizationTutorRepository,
            new BetaProperties(false));
    private final EntitlementService betaOpenService = new EntitlementService(
            organizationRepository, appUserRepository, tutorStudentRepository, organizationTutorRepository,
            new BetaProperties(true));

    private final StoryManifest paidStory = mock(StoryManifest.class);
    private final UUID parentId = UUID.randomUUID();
    private final CurrentUser parent = new CurrentUser(parentId, Role.PARENT, null);

    EntitlementServiceTest() {
        when(paidStory.requiresEntitlement()).thenReturn(true);
        when(appUserRepository.findById(any())).thenReturn(Optional.of(AppUser.builder().role(Role.PARENT).build()));
    }

    private static Organization organization(SubscriptionStatus status, Instant expiresAt, Integer seats) {
        return Organization.builder()
                .id(UUID.randomUUID()).subscriptionStatus(status).subscriptionExpiresAt(expiresAt).subscriptionSeats(seats)
                .build();
    }

    private static Organization activeOrganization(Integer seats) {
        return organization(SubscriptionStatus.ACTIVE, Instant.now().plus(10, ChronoUnit.DAYS), seats);
    }

    private ParentClassSeat childIn(Organization organization) {
        return new ParentClassSeat(UUID.randomUUID(), Instant.now(), organization);
    }

    @Test
    void parentWhoseChildIsInAnActiveOrganizationClassHasAccess() {
        when(tutorStudentRepository.findClassSeatsOfParent(parentId)).thenReturn(List.of(childIn(activeOrganization(null))));
        assertDoesNotThrow(() -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void oneActiveOrganizationIsEnoughWhenAnotherHasExpired() {
        Organization expired = organization(SubscriptionStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS), null);
        when(tutorStudentRepository.findClassSeatsOfParent(parentId))
                .thenReturn(List.of(childIn(expired), childIn(activeOrganization(null))));
        assertDoesNotThrow(() -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void expiredOrganizationDoesNotGrantAccess() {
        Organization expired = organization(SubscriptionStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS), null);
        when(tutorStudentRepository.findClassSeatsOfParent(parentId)).thenReturn(List.of(childIn(expired)));
        assertThrows(ApiException.class, () -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void parentWithNoClassAndNoPersonalSubscriptionHasNoAccess() {
        when(tutorStudentRepository.findClassSeatsOfParent(parentId)).thenReturn(List.of());
        assertThrows(ApiException.class, () -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void childWithinThePaidSeatsHasAccess() {
        Organization paidForThree = activeOrganization(3);
        ParentClassSeat third = childIn(paidForThree);
        when(tutorStudentRepository.findClassSeatsOfParent(parentId)).thenReturn(List.of(third));
        when(tutorStudentRepository.countEarlierInOrganization(
                eq(paidForThree.getId()), eq(third.linkedAt()), eq(third.studentId()))).thenReturn(2L);
        assertDoesNotThrow(() -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void childBeyondThePaidSeatsHasNoAccess() {
        Organization paidForThree = activeOrganization(3);
        ParentClassSeat fourth = childIn(paidForThree);
        when(tutorStudentRepository.findClassSeatsOfParent(parentId)).thenReturn(List.of(fourth));
        when(tutorStudentRepository.countEarlierInOrganization(
                eq(paidForThree.getId()), eq(fourth.linkedAt()), eq(fourth.studentId()))).thenReturn(3L);
        assertThrows(ApiException.class, () -> service.assertAccessible(paidStory, parent));
    }

    @Test
    void staleOrgIdClaimOnAParentTokenGrantsNothing() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(activeOrganization(null)));
        when(tutorStudentRepository.findClassSeatsOfParent(parentId)).thenReturn(List.of());
        CurrentUser staleToken = new CurrentUser(parentId, Role.PARENT, orgId);
        assertThrows(ApiException.class, () -> service.assertAccessible(paidStory, staleToken));
    }

    @Test
    void directorKeepsAccessThroughTheirOrganization() {
        UUID orgId = UUID.randomUUID();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(activeOrganization(null)));
        CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, orgId);
        assertDoesNotThrow(() -> service.assertAccessible(paidStory, director));
    }

    @Test
    void tutorGetsAccessThroughAnActiveOrganizationTheyBelongTo() {
        UUID tutorId = UUID.randomUUID();
        Organization expired = organization(SubscriptionStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS), 5);
        when(organizationTutorRepository.findOrganizationsOfTutor(tutorId))
                .thenReturn(List.of(expired, activeOrganization(1)));
        assertDoesNotThrow(() -> service.assertAccessible(paidStory, new CurrentUser(tutorId, Role.TUTOR, null)));
    }

    @Test
    void tutorInNoActiveOrganizationHasNoAccess() {
        UUID tutorId = UUID.randomUUID();
        when(organizationTutorRepository.findOrganizationsOfTutor(tutorId)).thenReturn(List.of());
        assertThrows(ApiException.class,
                () -> service.assertAccessible(paidStory, new CurrentUser(tutorId, Role.TUTOR, null)));
    }

    @Test
    void hasAccessMergesPersonalAndOrganizationSubscriptions() {
        AppUser orgOnlyParent = AppUser.builder().id(parentId).role(Role.PARENT).build();
        when(tutorStudentRepository.findClassSeatsOfParent(parentId)).thenReturn(List.of(childIn(activeOrganization(null))));
        assertEquals(true, service.hasAccess(orgOnlyParent));

        UUID payingParentId = UUID.randomUUID();
        AppUser payingParent = AppUser.builder()
                .id(payingParentId).role(Role.PARENT)
                .subscriptionStatus(SubscriptionStatus.ACTIVE)
                .subscriptionExpiresAt(Instant.now().plus(5, ChronoUnit.DAYS))
                .build();
        when(tutorStudentRepository.findClassSeatsOfParent(payingParentId)).thenReturn(List.of());
        assertEquals(true, service.hasAccess(payingParent));

        UUID nobodyId = UUID.randomUUID();
        when(tutorStudentRepository.findClassSeatsOfParent(nobodyId)).thenReturn(List.of());
        assertEquals(false, service.hasAccess(AppUser.builder().id(nobodyId).role(Role.PARENT).build()));
    }

    @Test
    void betaOpenAccessLetsTutorWithoutOrganizationIn() {
        CurrentUser tutor = new CurrentUser(UUID.randomUUID(), Role.TUTOR, null);
        when(appUserRepository.findById(tutor.userId()))
                .thenReturn(Optional.of(AppUser.builder().role(Role.TUTOR).subscriptionStatus(SubscriptionStatus.NONE).build()));
        when(organizationTutorRepository.findOrganizationsOfTutor(tutor.userId())).thenReturn(List.of());
        assertDoesNotThrow(() -> betaOpenService.assertAccessible(paidStory, tutor));
    }

    @Test
    void betaOpenAccessLetsDirectorWithoutOrganizationIn() {
        CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, null);
        when(appUserRepository.findById(director.userId()))
                .thenReturn(Optional.of(AppUser.builder().role(Role.DIRECTOR).subscriptionStatus(SubscriptionStatus.NONE).build()));
        assertDoesNotThrow(() -> betaOpenService.assertAccessible(paidStory, director));
    }

    @Test
    void betaOpenAccessDoesNotApplyToParents() {
        when(appUserRepository.findById(parentId))
                .thenReturn(Optional.of(AppUser.builder().role(Role.PARENT).subscriptionStatus(SubscriptionStatus.NONE).build()));
        when(tutorStudentRepository.findClassSeatsOfParent(parentId)).thenReturn(List.of());
        assertThrows(ApiException.class, () -> betaOpenService.assertAccessible(paidStory, parent));
    }

    @Test
    void tutorWithoutSubscriptionStaysLockedWhenBetaFlagIsOff() {
        CurrentUser tutor = new CurrentUser(UUID.randomUUID(), Role.TUTOR, null);
        when(appUserRepository.findById(tutor.userId()))
                .thenReturn(Optional.of(AppUser.builder().role(Role.TUTOR).subscriptionStatus(SubscriptionStatus.NONE).build()));
        when(organizationTutorRepository.findOrganizationsOfTutor(tutor.userId())).thenReturn(List.of());
        assertThrows(ApiException.class, () -> service.assertAccessible(paidStory, tutor));
    }

    @Test
    void hasAccessReflectsBetaOpenAccessForTutor() {
        AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).subscriptionStatus(SubscriptionStatus.NONE).build();
        when(organizationTutorRepository.findOrganizationsOfTutor(tutor.getId())).thenReturn(List.of());
        assertEquals(true, betaOpenService.hasAccess(tutor));
        assertEquals(false, service.hasAccess(tutor));
    }
}
