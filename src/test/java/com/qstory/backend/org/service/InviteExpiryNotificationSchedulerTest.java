package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassHomeroomInvite;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassHomeroomInviteRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutorInvite;
import com.qstory.backend.org.tutor.repository.OrganizationTutorInviteRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 초대 만료 알림(매일): 담임 초대·기관 선생님 초대 만료는 원장에게 한 번, 담임 초대는 이틀 전에도 한 번. */
class InviteExpiryNotificationSchedulerTest {

    private final ClassHomeroomInviteRepository homeroomInvites = mock(ClassHomeroomInviteRepository.class);
    private final OrganizationTutorInviteRepository orgInvites = mock(OrganizationTutorInviteRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final InviteExpiryNotificationScheduler scheduler =
            new InviteExpiryNotificationScheduler(homeroomInvites, orgInvites, users, publisher);

    private final Organization organization = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final AppUser director = AppUser.builder().id(UUID.randomUUID()).role(Role.DIRECTOR).build();
    private final ClassGroup classGroup = ClassGroup.builder().id(UUID.randomUUID()).organization(organization)
            .name("햇님반").build();

    @BeforeEach
    void setUp() {
        when(users.findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(organization.getId(), Role.DIRECTOR))
                .thenReturn(Optional.of(director));
        when(homeroomInvites.findExpiredUnusedBetween(any(), any())).thenReturn(List.of());
        when(homeroomInvites.findLiveExpiringBetween(any(), any())).thenReturn(List.of());
        when(orgInvites.findExpiredUnusedBetween(any(), any())).thenReturn(List.of());
    }

    @Test
    void expiredHomeroomInviteNotifiesDirector() {
        ClassHomeroomInvite invite = ClassHomeroomInvite.builder().id(UUID.randomUUID()).classGroup(classGroup).build();
        when(homeroomInvites.findExpiredUnusedBetween(any(), any())).thenReturn(List.of(invite));

        scheduler.notifyInviteExpiry();

        verify(publisher).publish(director.getId(), "invite-expired", "햇님반 담임 초대 코드가 만료됐어요",
                "반 화면에서 새 코드를 만들어 보내 주세요.", "/organization/classes/" + classGroup.getId(),
                "homeroom-invite-expired:" + invite.getId());
    }

    @Test
    void expiredOrganizationTutorInviteNotifiesDirector() {
        OrganizationTutorInvite invite = OrganizationTutorInvite.builder().id(UUID.randomUUID())
                .organization(organization).build();
        when(orgInvites.findExpiredUnusedBetween(any(), any())).thenReturn(List.of(invite));

        scheduler.notifyInviteExpiry();

        verify(publisher).publish(director.getId(), "invite-expired", "선생님 초대 코드가 만료됐어요",
                "선생님 관리에서 새 코드를 만들어 보내 주세요.", "/organization/tutors",
                "org-invite-expired:" + invite.getId());
    }

    @Test
    void expiringHomeroomInviteRemindsDirector() {
        ClassHomeroomInvite invite = ClassHomeroomInvite.builder().id(UUID.randomUUID()).classGroup(classGroup).build();
        when(homeroomInvites.findLiveExpiringBetween(any(), any())).thenReturn(List.of(invite));

        scheduler.notifyInviteExpiry();

        verify(publisher).publish(org.mockito.ArgumentMatchers.eq(director.getId()),
                org.mockito.ArgumentMatchers.eq("invite-expiring"),
                org.mockito.ArgumentMatchers.eq("햇님반 담임 초대 코드가 이틀 뒤 만료돼요"), any(),
                org.mockito.ArgumentMatchers.eq("/organization/classes/" + classGroup.getId()),
                org.mockito.ArgumentMatchers.eq("homeroom-invite-expiring:" + invite.getId()));
    }

    @Test
    void looksBackThreeDaysForExpiredAndTwoDaysAheadForExpiring() {
        Instant before = Instant.now();
        scheduler.notifyInviteExpiry();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(homeroomInvites).findExpiredUnusedBetween(from.capture(), to.capture());
        assertEquals(Duration.ofDays(3), Duration.between(from.getValue(), to.getValue()));
        assertTrue(!to.getValue().isBefore(before) && !to.getValue().isAfter(after));

        verify(homeroomInvites).findLiveExpiringBetween(from.capture(), to.capture());
        assertEquals(Duration.ofDays(2), Duration.between(from.getValue(), to.getValue()));
        verify(publisher, never()).publish(any(), any(), any(), any(), any(), any());
    }

    @Test
    void organizationWithoutDirectorIsSkipped() {
        when(users.findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(organization.getId(), Role.DIRECTOR))
                .thenReturn(Optional.empty());
        ClassHomeroomInvite invite = ClassHomeroomInvite.builder().id(UUID.randomUUID()).classGroup(classGroup).build();
        when(homeroomInvites.findExpiredUnusedBetween(any(), any())).thenReturn(List.of(invite));

        scheduler.notifyInviteExpiry();

        verify(publisher, never()).publish(any(), any(), any(), any(), any(), any());
    }
}
