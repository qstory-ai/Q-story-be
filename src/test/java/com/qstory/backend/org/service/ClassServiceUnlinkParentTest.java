package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.ConsentService;
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.identity.util.AuthValidator;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import com.qstory.backend.tutor.service.TutorStudentService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 보안 점검 H3 - 담임·원장이 잘못된 보호자 연결을 끊는다. 학생은 명단에 남고, 끊긴 보호자에게는 지난 리포트 열람권을 남기지 않는다. */
class ClassServiceUnlinkParentTest {

    private final ClassGroupRepository classGroups = mock(ClassGroupRepository.class);
    private final TutorStudentRepository students = mock(TutorStudentRepository.class);
    private final StoryCompletionRepository completions = mock(StoryCompletionRepository.class);
    private final NotificationPublisher notifications = mock(NotificationPublisher.class);
    private final ClassService service = new ClassService(
            classGroups, students, mock(OrganizationTutorRepository.class), mock(AppUserRepository.class),
            mock(OrganizationService.class), mock(JoinCodeGenerator.class), mock(AuthValidator.class),
            mock(PasswordEncoder.class), mock(JwtService.class), mock(TutorStudentService.class),
            mock(UserSummaryFactory.class), completions, mock(LessonRepository.class),
            mock(ClassHomeroomHistoryService.class), mock(ConsentService.class), notifications,
            mock(StudentClassHistoryService.class));

    private final Organization org = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("김선생").build();
    private final ClassGroup sunClass =
            ClassGroup.builder().id(UUID.randomUUID()).name("햇살반").organization(org).tutor(tutor).build();
    private final AppUser parent = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).displayName("낯선이").build();
    private TutorStudent student;

    @BeforeEach
    void setUp() {
        student = TutorStudent.builder().id(UUID.randomUUID()).name("민서").classGroup(sunClass).tutor(tutor)
                .linkedParentUser(parent).linkedAt(Instant.now()).status(TutorStudentStatus.CONFIRMED)
                .child(Child.builder().id(UUID.randomUUID()).parent(parent).name("민서").build()).build();
        when(classGroups.findById(sunClass.getId())).thenReturn(Optional.of(sunClass));
        when(students.lockById(student.getId())).thenReturn(Optional.of(student));
    }

    @Test
    void homeroomTutorUnlinksAndParentIsNotified() {
        service.unlinkParent(new CurrentUser(tutor.getId(), Role.TUTOR, null), sunClass.getId(), student.getId());

        assertNull(student.getLinkedParentUser());
        assertNull(student.getLinkedAt());
        assertNull(student.getChild());
        assertEquals(TutorStudentStatus.PENDING_PARENT, student.getStatus());
        verify(students).save(student);
        verify(completions, never()).keepParentAccess(any(), any(), any());
        verify(notifications).publish(eq(parent.getId()), eq("class-parent-unlinked"), eq("햇살반 반 연결이 해제됐어요"),
                anyString(), anyString(), anyString());
    }

    @Test
    void owningDirectorUnlinks() {
        service.unlinkParent(new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, org.getId()), sunClass.getId(), student.getId());
        assertNull(student.getLinkedParentUser());
    }

    @Test
    void otherTeachersAndDirectorsCannot() {
        assertThrows(ApiException.class, () -> service.unlinkParent(
                new CurrentUser(UUID.randomUUID(), Role.TUTOR, null), sunClass.getId(), student.getId()));
        assertThrows(ApiException.class, () -> service.unlinkParent(
                new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID()), sunClass.getId(), student.getId()));
        assertEquals(parent, student.getLinkedParentUser());
    }

    @Test
    void studentOfAnotherClassIsNotFound() {
        student.setClassGroup(ClassGroup.builder().id(UUID.randomUUID()).name("달님반").organization(org).build());
        ApiException e = assertThrows(ApiException.class, () -> service.unlinkParent(
                new CurrentUser(tutor.getId(), Role.TUTOR, null), sunClass.getId(), student.getId()));
        assertEquals(404, e.statusCode());
    }
}
