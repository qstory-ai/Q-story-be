package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.identity.util.AuthValidator;
import com.qstory.backend.org.dto.ClassStudentReportResponse;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import com.qstory.backend.tutor.service.TutorStudentService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 담임 변경 정책(Q-35): 지난 수업·리포트는 그때 선생님 것으로 남고, 명단 학생과 아직 시작하지 않은 수업만 새
 * 담임에게 넘어간다. 관리자는 두 선생님의 기록을 모두 보고, 담임 선생님은 자기가 진행한 기록만 본다.
 */
class ClassServiceHomeroomTest {

    private final ClassGroupRepository classGroupRepository = mock(ClassGroupRepository.class);
    private final TutorStudentRepository tutorStudentRepository = mock(TutorStudentRepository.class);
    private final OrganizationTutorRepository organizationTutorRepository = mock(OrganizationTutorRepository.class);
    private final StoryCompletionRepository storyCompletionRepository = mock(StoryCompletionRepository.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final ClassHomeroomHistoryService historyService = mock(ClassHomeroomHistoryService.class);
    private final ClassService service = new ClassService(
            classGroupRepository, tutorStudentRepository, organizationTutorRepository, mock(AppUserRepository.class),
            mock(OrganizationService.class), mock(JoinCodeGenerator.class), mock(AuthValidator.class),
            mock(PasswordEncoder.class), mock(JwtService.class), mock(TutorStudentService.class),
            mock(UserSummaryFactory.class), storyCompletionRepository, lessonRepository, historyService);

    private final Organization organization = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, organization.getId());
    private final AppUser oldTutor = tutor("김선생");
    private final AppUser newTutor = tutor("이선생");
    private ClassGroup classGroup;

    @BeforeEach
    void setUp() {
        classGroup = ClassGroup.builder().id(UUID.randomUUID()).organization(organization).name("햇님반")
                .joinCode("SUN123").createdAt(Instant.now()).build();
        when(classGroupRepository.findById(classGroup.getId())).thenReturn(Optional.of(classGroup));
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), newTutor.getId()))
                .thenReturn(Optional.of(OrganizationTutor.builder().organization(organization).tutor(newTutor).build()));
        when(tutorStudentRepository.findByClassGroup_IdAndTutorIsNullAndDeletedAtIsNull(classGroup.getId()))
                .thenReturn(List.of());
        when(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void assigningFirstHomeroomMovesWaitingStudentsAndStartsHistory() {
        TutorStudent waiting = student(null, null);
        when(tutorStudentRepository.findByClassGroup_IdAndTutorIsNullAndDeletedAtIsNull(classGroup.getId()))
                .thenReturn(List.of(waiting));

        service.assignHomeroom(director, classGroup.getId(), newTutor.getId());

        assertSame(newTutor, classGroup.getTutor());
        assertSame(newTutor, waiting.getTutor());
        verify(historyService).start(eq(classGroup), eq(newTutor), any());
        verify(lessonRepository, never()).findByTutor_IdAndClassGroup_IdAndStatus(any(), any(), any());
    }

    @Test
    void changingHomeroomMovesRosterAndScheduledLessonsOnly() {
        classGroup.setTutor(oldTutor);
        TutorStudent minseo = student(oldTutor, null);
        TutorStudent duplicate = student(oldTutor, Child.builder().id(UUID.randomUUID()).build());
        when(tutorStudentRepository.findByClassGroup_IdAndTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(
                classGroup.getId(), oldTutor.getId())).thenReturn(List.of(minseo, duplicate));
        // 새 담임이 이 아이를 다른 반에서 이미 학생으로 갖고 있다 - 넘기지 못한다.
        when(tutorStudentRepository.existsByTutor_IdAndChild_IdAndDeletedAtIsNull(
                newTutor.getId(), duplicate.getChild().getId())).thenReturn(true);
        Lesson scheduled = lesson(oldTutor, LessonStatus.SCHEDULED, minseo, duplicate);
        when(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                oldTutor.getId(), classGroup.getId(), LessonStatus.SCHEDULED)).thenReturn(List.of(scheduled));

        service.assignHomeroom(director, classGroup.getId(), newTutor.getId());

        assertSame(newTutor, classGroup.getTutor());
        assertSame(newTutor, minseo.getTutor());
        assertNull(duplicate.getTutor(), "넘기지 못한 학생은 담임 없이 명단에 남는다");
        assertSame(newTutor, scheduled.getTutor());
        assertEquals(List.of(minseo), new ArrayList<>(scheduled.getStudents()));
        verify(historyService).start(eq(classGroup), eq(newTutor), any());
        // 진행 중·완료된 수업은 조회조차 하지 않는다 - 그때 선생님 것으로 남는다.
        verify(lessonRepository, never()).findByTutor_IdAndClassGroup_IdAndStatus(
                oldTutor.getId(), classGroup.getId(), LessonStatus.COMPLETED);
        verify(lessonRepository, never()).findByTutor_IdAndClassGroup_IdAndStatus(
                oldTutor.getId(), classGroup.getId(), LessonStatus.IN_PROGRESS);
    }

    @Test
    void reassigningSameTutorIsNoOp() {
        classGroup.setTutor(newTutor);

        service.assignHomeroom(director, classGroup.getId(), newTutor.getId());

        verify(historyService, never()).start(any(), any(), any());
        verify(tutorStudentRepository, never()).saveAll(any());
    }

    @Test
    void tutorOutsideOrganizationIsRejected() {
        classGroup.setTutor(oldTutor);
        UUID stranger = UUID.randomUUID();
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), stranger))
                .thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class,
                () -> service.assignHomeroom(director, classGroup.getId(), stranger));
        assertEquals(404, error.statusCode());
        assertSame(oldTutor, classGroup.getTutor());
    }

    @Test
    void otherOrganizationsDirectorCannotChangeHomeroom() {
        CurrentUser otherDirector = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID());

        ApiException error = assertThrows(ApiException.class,
                () -> service.assignHomeroom(otherDirector, classGroup.getId(), newTutor.getId()));
        assertEquals(403, error.statusCode());
    }

    @Test
    void directorSeesBothTutorsReportsButHomeroomTutorOnlyTheirOwn() {
        classGroup.setTutor(newTutor);
        TutorStudent minseo = student(newTutor, null);
        when(tutorStudentRepository.findById(minseo.getId())).thenReturn(Optional.of(minseo));
        StoryCompletion before = completion(oldTutor);
        StoryCompletion after = completion(newTutor);
        when(storyCompletionRepository.findByParticipant(minseo.getId())).thenReturn(List.of(after, before));

        List<ClassStudentReportResponse> forDirector =
                service.listStudentReports(director, classGroup.getId(), minseo.getId());
        assertEquals(2, forDirector.size());
        assertEquals("이선생", forDirector.get(0).tutorDisplayName());
        assertEquals("김선생", forDirector.get(1).tutorDisplayName());

        CurrentUser homeroom = new CurrentUser(newTutor.getId(), Role.TUTOR, null);
        List<ClassStudentReportResponse> forTutor =
                service.listStudentReports(homeroom, classGroup.getId(), minseo.getId());
        assertEquals(1, forTutor.size());
        assertEquals(after.getId(), forTutor.get(0).id());
    }

    @Test
    void previousHomeroomTutorCannotOpenTheClassAnymore() {
        classGroup.setTutor(newTutor);
        CurrentUser previous = new CurrentUser(oldTutor.getId(), Role.TUTOR, null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.listStudentReports(previous, classGroup.getId(), UUID.randomUUID()));
        assertEquals(403, error.statusCode());
    }

    @Test
    void studentOfAnotherClassIsNotFound() {
        TutorStudent elsewhere = student(newTutor, null);
        elsewhere.setClassGroup(ClassGroup.builder().id(UUID.randomUUID()).build());
        when(tutorStudentRepository.findById(elsewhere.getId())).thenReturn(Optional.of(elsewhere));

        ApiException error = assertThrows(ApiException.class,
                () -> service.listStudentReports(director, classGroup.getId(), elsewhere.getId()));
        assertEquals(404, error.statusCode());
    }

    private static AppUser tutor(String name) {
        return AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName(name).build();
    }

    private TutorStudent student(AppUser tutor, Child child) {
        return TutorStudent.builder().id(UUID.randomUUID()).tutor(tutor).classGroup(classGroup).child(child)
                .name("아이").ageBand("5세").createdAt(Instant.now()).build();
    }

    private Lesson lesson(AppUser tutor, LessonStatus status, TutorStudent... students) {
        return Lesson.builder().id(UUID.randomUUID()).tutor(tutor).classGroup(classGroup).status(status)
                .name("목요일 동화 수업").students(new LinkedHashSet<>(List.of(students)))
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    private StoryCompletion completion(AppUser tutor) {
        return StoryCompletion.builder().id(UUID.randomUUID()).user(tutor).organization(organization)
                .classGroup(classGroup).groupSession(true).storyId("HG").completedAt(Instant.now()).build();
    }
}
