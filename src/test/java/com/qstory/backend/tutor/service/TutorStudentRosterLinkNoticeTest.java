package com.qstory.backend.tutor.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.service.StudentClassHistoryService;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 보안 점검 H3 - 반 코드만 알면 명단에서 남의 아이를 골라 이을 수 있으므로, 선생님이 미리 올려 둔 학생에 학부모가 이어지면 담임과
 * 원장 모두에게 "확인해 주세요" 알림을 보낸다. 새 학생으로 들어온 경우는 예전처럼 담임에게만 알린다.
 */
class TutorStudentRosterLinkNoticeTest {

    private final TutorStudentRepository students = mock(TutorStudentRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final NotificationPublisher notifications = mock(NotificationPublisher.class);
    private final ChildRepository children = mock(ChildRepository.class);
    private final TutorStudentService service = new TutorStudentService(
            students, users, notifications, children, mock(LessonRepository.class), mock(StudentClassHistoryService.class));

    private final AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("김선생").build();
    private final AppUser director = AppUser.builder().id(UUID.randomUUID()).role(Role.DIRECTOR).displayName("원장").build();
    private final AppUser parent = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).displayName("낯선이").build();
    private final Organization org = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final ClassGroup sunClass =
            ClassGroup.builder().id(UUID.randomUUID()).name("햇살반").organization(org).tutor(tutor).build();
    private final Child child = Child.builder().id(UUID.randomUUID()).parent(parent).name("민서").birthYear(2020).build();

    @BeforeEach
    void setUp() {
        when(children.findByIdAndParent_Id(child.getId(), parent.getId())).thenReturn(Optional.of(child));
        when(students.saveAndFlush(any(TutorStudent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(org.getId(), Role.DIRECTOR))
                .thenReturn(Optional.of(director));
    }

    @Test
    void pickingAPreRegisteredStudentAsksHomeroomAndDirectorToVerify() {
        TutorStudent preRegistered = TutorStudent.builder().id(UUID.randomUUID()).name("민서").classGroup(sunClass)
                .tutor(tutor).birthYear(2020).createdAt(Instant.now()).build();
        when(students.lockById(preRegistered.getId())).thenReturn(Optional.of(preRegistered));

        service.enrollParentInClass(parent, sunClass, null, null, child.getId(), preRegistered.getId());

        String dedup = "class-roster-link-check:" + preRegistered.getId() + ":" + parent.getId();
        verify(notifications).publish(eq(tutor.getId()), anyString(), eq("민서 보호자 연결을 확인해 주세요"),
                contains("낯선이님이 햇살반 반 명단의 민서"), eq("/tutor/students/" + preRegistered.getId()), eq(dedup));
        verify(notifications).publish(eq(director.getId()), anyString(), eq("민서 보호자 연결을 확인해 주세요"),
                contains("연결을 해제할 수 있어요"), eq("/organization/classes/" + sunClass.getId()), eq(dedup));
    }

    @Test
    void newStudentKeepsThePlainJoinNotice() {
        service.enrollParentInClass(parent, sunClass, null, null, child.getId(), null);

        verify(notifications).publish(eq(tutor.getId()), eq("tutor-class-parent-joined"),
                eq("민서 부모님이 반 코드로 들어왔어요"), anyString(), anyString(), anyString());
        verify(notifications, never()).publish(eq(director.getId()), anyString(), anyString(), anyString(), anyString(),
                anyString());
    }
}
