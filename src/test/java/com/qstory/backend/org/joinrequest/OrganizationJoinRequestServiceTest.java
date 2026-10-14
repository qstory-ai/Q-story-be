package com.qstory.backend.org.joinrequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.joinrequest.OrganizationJoinRequest.Status;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.tutor.service.OrganizationTutorService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 선생님 소속 신청(083): 공공 기관과 연결된 기관에만, 이미 소속이면 409, 대기 신청은 하나(다시 눌러도 같은 신청).
 * 원장만, 자기 기관의 신청만 수락·거절한다. 수락은 초대 수락과 같은 linkTutor로 소속을 만들고 선생님에게 알린다.
 */
class OrganizationJoinRequestServiceTest {

    private final OrganizationJoinRequestRepository requestRepository = mock(OrganizationJoinRequestRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final OrganizationTutorRepository organizationTutorRepository = mock(OrganizationTutorRepository.class);
    private final OrganizationTutorService organizationTutorService = mock(OrganizationTutorService.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
    private final OrganizationJoinRequestService service = new OrganizationJoinRequestService(
            requestRepository, organizationRepository, organizationTutorRepository, organizationTutorService,
            userRepository, notificationPublisher);

    private final Organization organization = Organization.builder()
            .id(UUID.randomUUID()).name("햇살유치원").publicInstitutionId(UUID.randomUUID()).build();
    private final AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("김하늘").build();
    private final AppUser director = AppUser.builder().id(UUID.randomUUID()).role(Role.DIRECTOR).build();
    private final CurrentUser tutorCaller = new CurrentUser(tutor.getId(), Role.TUTOR, null);
    private final CurrentUser directorCaller = new CurrentUser(director.getId(), Role.DIRECTOR, organization.getId());

    @BeforeEach
    void setUp() {
        when(organizationRepository.findById(organization.getId())).thenReturn(Optional.of(organization));
        when(userRepository.findById(tutor.getId())).thenReturn(Optional.of(tutor));
        when(userRepository.findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(organization.getId(), Role.DIRECTOR))
                .thenReturn(Optional.of(director));
        when(requestRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(requestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createNotifiesDirector() {
        var response = service.create(tutorCaller, organization.getId());

        assertEquals("PENDING", response.status());
        assertEquals("햇살유치원", response.organizationName());
        verify(notificationPublisher).publish(eq(director.getId()), eq("org-join-requested"),
                eq("김하늘 선생님이 소속을 신청했어요"), anyString(), eq("/organization/tutors"),
                eq("org-join-requested:" + response.id()));
    }

    @Test
    void pendingRequestIsReturnedAgainWithoutNewNotification() {
        OrganizationJoinRequest pending = request(Status.PENDING);
        when(requestRepository.findFirstByOrganization_IdAndTutor_IdAndStatus(organization.getId(), tutor.getId(), Status.PENDING))
                .thenReturn(Optional.of(pending));

        assertEquals(pending.getId(), service.create(tutorCaller, organization.getId()).id());
        verify(requestRepository, never()).saveAndFlush(any());
        verify(notificationPublisher, never()).publish(any(), any(), any(), any(), any(), any());
    }

    @Test
    void alreadyMemberAndUnlinkedOrganizationAreRejected() {
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), tutor.getId()))
                .thenReturn(Optional.of(OrganizationTutor.builder().build()));
        ApiException member = assertThrows(ApiException.class, () -> service.create(tutorCaller, organization.getId()));
        assertEquals(ErrorCode.ALREADY_ORGANIZATION_MEMBER, member.code());

        Organization manual = Organization.builder().id(UUID.randomUUID()).name("직접 입력 기관").build();
        when(organizationRepository.findById(manual.getId())).thenReturn(Optional.of(manual));
        ApiException notFound = assertThrows(ApiException.class, () -> service.create(tutorCaller, manual.getId()));
        assertEquals(ErrorCode.NOT_FOUND, notFound.code());
    }

    @Test
    void onlyTutorsCanRequest() {
        assertThrows(ApiException.class, () -> service.create(directorCaller, organization.getId()));
    }

    @Test
    void acceptLinksTutorLikeInviteAndNotifiesTutor() {
        OrganizationJoinRequest pending = request(Status.PENDING);
        when(requestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        var response = service.accept(directorCaller, organization.getId(), pending.getId());

        assertEquals("ACCEPTED", response.status());
        verify(organizationTutorService).linkTutor(eq(organization), eq(tutor), isNull());
        verify(notificationPublisher).publish(eq(tutor.getId()), eq("org-join-accepted"), eq("햇살유치원 소속이 됐어요"),
                anyString(), eq("/tutor/join-organization"), eq("org-join-accepted:" + pending.getId()));
    }

    @Test
    void directorOfAnotherOrganizationCannotDecide() {
        OrganizationJoinRequest pending = request(Status.PENDING);
        when(requestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        CurrentUser otherDirector = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID());

        assertThrows(ApiException.class, () -> service.accept(otherDirector, organization.getId(), pending.getId()));
        // 자기 기관 id로 남의 신청 id를 넣어도 못 찾는다.
        Organization mine = Organization.builder().id(otherDirector.orgId()).name("다른 기관").build();
        when(organizationRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        ApiException notFound = assertThrows(ApiException.class,
                () -> service.reject(otherDirector, mine.getId(), pending.getId()));
        assertEquals(ErrorCode.NOT_FOUND, notFound.code());
        verify(organizationTutorService, never()).linkTutor(any(), any(), any());
    }

    @Test
    void rejectNotifiesTutorAndDecidedRequestCannotBeAccepted() {
        OrganizationJoinRequest pending = request(Status.PENDING);
        when(requestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertEquals("REJECTED", service.reject(directorCaller, organization.getId(), pending.getId()).status());
        verify(notificationPublisher).publish(eq(tutor.getId()), eq("org-join-rejected"), anyString(), anyString(),
                eq("/tutor/join-organization"), eq("org-join-rejected:" + pending.getId()));

        ApiException decided = assertThrows(ApiException.class,
                () -> service.accept(directorCaller, organization.getId(), pending.getId()));
        assertEquals(ErrorCode.JOIN_REQUEST_ALREADY_DECIDED, decided.code());
    }

    @Test
    void tutorCancelsOwnPendingOnly() {
        OrganizationJoinRequest pending = request(Status.PENDING);
        when(requestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        CurrentUser stranger = new CurrentUser(UUID.randomUUID(), Role.TUTOR, null);
        assertThrows(ApiException.class, () -> service.cancel(stranger, pending.getId()));

        assertEquals("CANCELED", service.cancel(tutorCaller, pending.getId()).status());
        assertEquals("CANCELED", service.cancel(tutorCaller, pending.getId()).status(), "다시 취소해도 그대로");
        ApiException decided = assertThrows(ApiException.class,
                () -> service.accept(directorCaller, organization.getId(), pending.getId()));
        assertEquals(ErrorCode.JOIN_REQUEST_ALREADY_DECIDED, decided.code());
    }

    private OrganizationJoinRequest request(Status status) {
        return OrganizationJoinRequest.builder()
                .id(UUID.randomUUID()).organization(organization).tutor(tutor).status(status)
                .createdAt(Instant.now()).build();
    }
}
