package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.ConsentService;
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.identity.util.AuthValidator;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.dto.ClassHomeroomInvitePreviewResponse;
import com.qstory.backend.org.dto.ClassHomeroomInviteResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassHomeroomInvite;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.repository.ClassHomeroomInviteRepository;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.repository.OrganizationTutorInviteRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.tutor.service.OrganizationTutorService;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import com.qstory.backend.tutor.service.TutorStudentService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 반 담임 초대(074): 발급은 원장만, 새로 발급하면 이전 초대는 못 쓴다. 선생님이 수락하면 기관 소속(기관 초대 수락과 같은
 * 경로) + 담임 배정(원장 배정과 같은 경로, 담임 이력 포함) + 초대 사용 처리. 이미 쓴/만료된 코드는 410.
 */
class ClassHomeroomInviteServiceTest {

    private final ClassHomeroomInviteRepository inviteRepository = mock(ClassHomeroomInviteRepository.class);
    private final ClassGroupRepository classGroupRepository = mock(ClassGroupRepository.class);
    private final TutorStudentRepository tutorStudentRepository = mock(TutorStudentRepository.class);
    private final OrganizationTutorRepository organizationTutorRepository = mock(OrganizationTutorRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final ClassHomeroomHistoryService historyService = mock(ClassHomeroomHistoryService.class);
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);

    private final ClassService classService = new ClassService(
            classGroupRepository, tutorStudentRepository, organizationTutorRepository, userRepository,
            mock(OrganizationService.class), new JoinCodeGenerator(), mock(AuthValidator.class),
            mock(PasswordEncoder.class), mock(JwtService.class), mock(TutorStudentService.class),
            mock(UserSummaryFactory.class), mock(StoryCompletionRepository.class), lessonRepository, historyService,
            mock(ConsentService.class));
    private final OrganizationTutorService organizationTutorService = new OrganizationTutorService(
            organizationTutorRepository, mock(OrganizationTutorInviteRepository.class), mock(OrganizationRepository.class),
            userRepository, new SecureTokenGenerator(), new JoinCodeGenerator(), notificationPublisher,
            classGroupRepository, tutorStudentRepository, lessonRepository, historyService);
    private final ClassHomeroomInviteService service = new ClassHomeroomInviteService(
            inviteRepository, classService, organizationTutorService, userRepository,
            new SecureTokenGenerator(), new JoinCodeGenerator());

    private final Organization organization = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final AppUser directorUser = AppUser.builder().id(UUID.randomUUID()).role(Role.DIRECTOR)
            .displayName("원장").build();
    private final CurrentUser director = new CurrentUser(directorUser.getId(), Role.DIRECTOR, organization.getId());
    private final AppUser newTutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("이선생").build();
    private final CurrentUser tutorCaller = new CurrentUser(newTutor.getId(), Role.TUTOR, null);
    private ClassGroup classGroup;

    @BeforeEach
    void setUp() {
        classGroup = ClassGroup.builder().id(UUID.randomUUID()).organization(organization).name("햇님반")
                .joinCode("SUN12345").createdAt(Instant.now()).build();
        when(classGroupRepository.findById(classGroup.getId())).thenReturn(Optional.of(classGroup));
        when(inviteRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(userRepository.getReferenceById(directorUser.getId())).thenReturn(directorUser);
        when(userRepository.findById(newTutor.getId())).thenReturn(Optional.of(newTutor));
        when(userRepository.findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(organization.getId(), Role.DIRECTOR))
                .thenReturn(Optional.of(directorUser));
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), newTutor.getId()))
                .thenReturn(Optional.empty());
        when(organizationTutorRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(tutorStudentRepository.findByClassGroup_IdAndTutorIsNullAndDeletedAtIsNull(classGroup.getId()))
                .thenReturn(List.of());
        when(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(any(), any(), any())).thenReturn(List.of());
    }

    /* ---------------------------------------------------------- issue */

    @Test
    void issuingDeletesThePreviousUnusedInviteBeforeSavingTheNewOne() {
        ClassHomeroomInviteResponse response = service.issue(director, classGroup.getId());

        InOrder order = inOrder(inviteRepository);
        order.verify(inviteRepository).deleteUnusedByClassGroupId(classGroup.getId());
        ArgumentCaptor<ClassHomeroomInvite> saved = ArgumentCaptor.forClass(ClassHomeroomInvite.class);
        order.verify(inviteRepository).save(saved.capture());

        ClassHomeroomInvite invite = saved.getValue();
        assertSame(classGroup, invite.getClassGroup());
        assertSame(directorUser, invite.getCreatedBy());
        assertNull(invite.getUsedAt());
        assertEquals(8, response.shortCode().length());
        assertTrue(response.token().length() >= 32, "기관 초대와 같은 긴 무작위 token");
        assertEquals(Duration.ofDays(14), Duration.between(invite.getCreatedAt(), response.expiresAt()));
    }

    @Test
    void issuingTwiceGivesDifferentCodes() {
        ClassHomeroomInviteResponse first = service.issue(director, classGroup.getId());
        ClassHomeroomInviteResponse second = service.issue(director, classGroup.getId());

        assertTrue(!first.shortCode().equals(second.shortCode()) && !first.token().equals(second.token()));
        verify(inviteRepository, org.mockito.Mockito.times(2)).deleteUnusedByClassGroupId(classGroup.getId());
    }

    @Test
    void onlyTheOwningDirectorCanIssue() {
        CurrentUser otherDirector = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID());
        for (CurrentUser caller : List.of(otherDirector, tutorCaller)) {
            ApiException error = assertThrows(ApiException.class, () -> service.issue(caller, classGroup.getId()));
            assertEquals(403, error.statusCode());
        }
        verify(inviteRepository, never()).deleteUnusedByClassGroupId(any());
        verify(inviteRepository, never()).save(any());
    }

    @Test
    void currentIs404WhenThereIsNoActiveInvite() {
        when(inviteRepository.findFirstByClassGroup_IdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(classGroup.getId()), any())).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> service.current(director, classGroup.getId()));
        assertEquals(404, error.statusCode());
    }

    @Test
    void currentReturnsTheActiveInvite() {
        ClassHomeroomInvite invite = invite(null, Instant.now().plus(Duration.ofDays(3)));
        when(inviteRepository.findFirstByClassGroup_IdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(classGroup.getId()), any())).thenReturn(Optional.of(invite));

        ClassHomeroomInviteResponse response = service.current(director, classGroup.getId());
        assertEquals(invite.getShortCode(), response.shortCode());
        assertEquals(invite.getToken(), response.token());
    }

    /* ---------------------------------------------------------- preview */

    @Test
    void previewShowsOrganizationClassAndCurrentHomeroom() {
        ClassHomeroomInvite invite = invite(null, Instant.now().plus(Duration.ofDays(3)));
        when(inviteRepository.findByShortCode("ABCD2345")).thenReturn(Optional.of(invite));

        ClassHomeroomInvitePreviewResponse unassigned = service.preview(" abcd2345 ");
        assertEquals("햇살유치원", unassigned.organizationName());
        assertEquals("햇님반", unassigned.className());
        assertEquals(invite.getExpiresAt(), unassigned.expiresAt());
        assertNull(unassigned.currentHomeroomName(), "담임 미정");

        classGroup.setTutor(AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("김선생").build());
        assertEquals("김선생", service.preview("ABCD2345").currentHomeroomName());
    }

    @Test
    void previewStatuses() {
        when(inviteRepository.findByShortCode("UNKNOWN2")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.preview("UNKNOWN2")).statusCode());

        ClassHomeroomInvite expired = invite(null, Instant.now().minusSeconds(1));
        when(inviteRepository.findByShortCode("ABCD2345")).thenReturn(Optional.of(expired));
        ApiException expiredError = assertThrows(ApiException.class, () -> service.preview("ABCD2345"));
        assertEquals(410, expiredError.statusCode());
        assertEquals(ErrorCode.INVALID_INVITE, expiredError.code());

        ClassHomeroomInvite used = invite(Instant.now(), Instant.now().plus(Duration.ofDays(3)));
        when(inviteRepository.findByShortCode("ABCD2345")).thenReturn(Optional.of(used));
        assertEquals(410, assertThrows(ApiException.class, () -> service.preview("ABCD2345")).statusCode());
    }

    /* ---------------------------------------------------------- accept */

    @Test
    void acceptingLinksTheOrganizationAssignsHomeroomAndMarksUsed() {
        TutorStudent waiting = TutorStudent.builder().id(UUID.randomUUID()).classGroup(classGroup).name("민서")
                .ageBand("5세").createdAt(Instant.now()).build();
        when(tutorStudentRepository.findByClassGroup_IdAndTutorIsNullAndDeletedAtIsNull(classGroup.getId()))
                .thenReturn(List.of(waiting));
        ClassHomeroomInvite invite = invite(null, Instant.now().plus(Duration.ofDays(3)));
        when(inviteRepository.lockByShortCode("ABCD2345")).thenReturn(Optional.of(invite));

        ClassResponse response = service.accept(tutorCaller, "abcd2345");

        ArgumentCaptor<OrganizationTutor> link = ArgumentCaptor.forClass(OrganizationTutor.class);
        verify(organizationTutorRepository).save(link.capture());
        assertSame(organization, link.getValue().getOrganization());
        assertSame(newTutor, link.getValue().getTutor());
        verify(notificationPublisher).publish(eq(directorUser.getId()), eq("org-tutor-invite-accepted"),
                anyString(), anyString(), anyString(), eq("class-homeroom-invite-accepted:" + invite.getId()));

        assertSame(newTutor, classGroup.getTutor());
        assertEquals(newTutor.getId(), response.tutorId());
        assertSame(newTutor, waiting.getTutor(), "담임 미정일 때 들어온 학생이 새 담임에게 간다");
        verify(historyService).start(eq(classGroup), eq(newTutor), any());

        assertNotNull(invite.getUsedAt());
        assertSame(newTutor, invite.getUsedBy());
        verify(inviteRepository).save(invite);
    }

    @Test
    void alreadyLinkedTutorIsNotLinkedAgain() {
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), newTutor.getId()))
                .thenReturn(Optional.of(OrganizationTutor.builder().organization(organization).tutor(newTutor).build()));
        ClassHomeroomInvite invite = invite(null, Instant.now().plus(Duration.ofDays(3)));
        when(inviteRepository.lockByShortCode("ABCD2345")).thenReturn(Optional.of(invite));

        service.accept(tutorCaller, "ABCD2345");

        verify(organizationTutorRepository, never()).save(any());
        verify(notificationPublisher, never()).publish(any(), any(), any(), any(), any(), any());
        assertSame(newTutor, classGroup.getTutor());
        assertNotNull(invite.getUsedAt());
    }

    @Test
    void acceptingReplacesAnExistingHomeroom() {
        AppUser oldTutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("김선생").build();
        classGroup.setTutor(oldTutor);
        when(tutorStudentRepository.findByClassGroup_IdAndTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(
                classGroup.getId(), oldTutor.getId())).thenReturn(List.of());
        ClassHomeroomInvite invite = invite(null, Instant.now().plus(Duration.ofDays(3)));
        when(inviteRepository.lockByShortCode("ABCD2345")).thenReturn(Optional.of(invite));

        service.accept(tutorCaller, "ABCD2345");

        assertSame(newTutor, classGroup.getTutor());
        verify(historyService).start(eq(classGroup), eq(newTutor), any());
    }

    @Test
    void usedOrExpiredInviteIs410AndChangesNothing() {
        ClassHomeroomInvite used = invite(Instant.now(), Instant.now().plus(Duration.ofDays(3)));
        when(inviteRepository.lockByShortCode("USED2345")).thenReturn(Optional.of(used));
        ClassHomeroomInvite expired = invite(null, Instant.now().minusSeconds(1));
        when(inviteRepository.lockByShortCode("EXPD2345")).thenReturn(Optional.of(expired));

        for (String code : List.of("USED2345", "EXPD2345")) {
            ApiException error = assertThrows(ApiException.class, () -> service.accept(tutorCaller, code));
            assertEquals(410, error.statusCode());
            assertEquals(ErrorCode.INVALID_INVITE, error.code());
        }
        verify(organizationTutorRepository, never()).save(any());
        verify(historyService, never()).start(any(), any(), any());
        assertNull(classGroup.getTutor());
    }

    @Test
    void acceptingTwiceFailsTheSecondTime() {
        ClassHomeroomInvite invite = invite(null, Instant.now().plus(Duration.ofDays(3)));
        when(inviteRepository.lockByShortCode("ABCD2345")).thenReturn(Optional.of(invite));
        service.accept(tutorCaller, "ABCD2345");

        AppUser another = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("박선생").build();
        when(userRepository.findById(another.getId())).thenReturn(Optional.of(another));
        ApiException error = assertThrows(ApiException.class,
                () -> service.accept(new CurrentUser(another.getId(), Role.TUTOR, null), "ABCD2345"));
        assertEquals(410, error.statusCode());
        assertSame(newTutor, classGroup.getTutor());
    }

    @Test
    void unknownCodeIs404() {
        when(inviteRepository.lockByShortCode("UNKNOWN2")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.accept(tutorCaller, "UNKNOWN2")).statusCode());
    }

    @Test
    void onlyTutorsCanAccept() {
        for (Role role : List.of(Role.DIRECTOR, Role.PARENT)) {
            ApiException error = assertThrows(ApiException.class,
                    () -> service.accept(new CurrentUser(UUID.randomUUID(), role, null), "ABCD2345"));
            assertEquals(403, error.statusCode());
        }
        verify(inviteRepository, never()).lockByShortCode(any());
    }

    private ClassHomeroomInvite invite(Instant usedAt, Instant expiresAt) {
        return ClassHomeroomInvite.builder().id(UUID.randomUUID()).classGroup(classGroup)
                .token("token-" + UUID.randomUUID()).shortCode("ABCD2345")
                .createdAt(Instant.now().minus(Duration.ofDays(1))).expiresAt(expiresAt).usedAt(usedAt).build();
    }
}
