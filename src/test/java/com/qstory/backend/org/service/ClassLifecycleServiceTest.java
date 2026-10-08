package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.qstory.backend.org.dto.ClassHistoryEntryResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.MoveStudentsRequest;
import com.qstory.backend.org.dto.MoveStudentsResponse;
import com.qstory.backend.org.dto.RenameClassRequest;
import com.qstory.backend.org.dto.SkippedStudent;
import com.qstory.backend.org.dto.TermTransitionRequest;
import com.qstory.backend.org.dto.TermTransitionRequest.Decision;
import com.qstory.backend.org.dto.TermTransitionResponse;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassMembershipReason;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.entity.TutorStudentClassHistory;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.repository.ClassHomeroomInviteRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 반 수명주기(076): 이름 바꾸기, 지난 반 보관(지금 학생이 있으면 409), 학생 옮기기(같은 학생 행, 이력, 수업, 알림),
 * 학기 넘기기(모든 학생에게 정해야 하고 한 번에), 반 이력 열람 권한.
 */
class ClassLifecycleServiceTest {

    private final ClassService classService = mock(ClassService.class);
    private final ClassGroupRepository classGroupRepository = mock(ClassGroupRepository.class);
    private final TutorStudentRepository tutorStudentRepository = mock(TutorStudentRepository.class);
    private final StudentClassHistoryService classHistoryService = mock(StudentClassHistoryService.class);
    private final ClassHomeroomHistoryService homeroomHistoryService = mock(ClassHomeroomHistoryService.class);
    private final ClassHomeroomInviteRepository inviteRepository = mock(ClassHomeroomInviteRepository.class);
    private final OrganizationTutorRepository organizationTutorRepository = mock(OrganizationTutorRepository.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
    private final ClassLifecycleService service = new ClassLifecycleService(
            classService, classGroupRepository, tutorStudentRepository, classHistoryService, homeroomHistoryService,
            inviteRepository, organizationTutorRepository, lessonRepository, userRepository, notificationPublisher);

    private final Organization organization = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, organization.getId());
    private final AppUser kim = tutor("김선생");
    private final AppUser lee = tutor("이선생");
    private final AppUser parent = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).displayName("민서맘").build();
    private final AtomicLong historyIds = new AtomicLong(100);
    private ClassGroup sun;
    private ClassGroup star;

    @BeforeEach
    void setUp() {
        sun = classGroup("햇님반", kim);
        star = classGroup("별님반", lee);
        when(classService.requireOwnedByDirector(director, sun.getId())).thenReturn(sun);
        when(classService.requireOwnedByDirector(director, star.getId())).thenReturn(star);
        when(classGroupRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(any(), any(), any())).thenReturn(List.of());
        when(userRepository.getReferenceById(director.userId()))
                .thenReturn(AppUser.builder().id(director.userId()).role(Role.DIRECTOR).build());
        when(classHistoryService.transfer(any(), any(), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> history(call.getArgument(0), call.getArgument(1)));
        when(classHistoryService.close(any(), any(), anyString(), any(), any()))
                .thenAnswer(call -> history(call.getArgument(0), call.getArgument(1)));
    }

    /* ---------------------------------------------------------- rename */

    @Test
    void renameTrimsAndKeepsOtherFields() {
        ClassResponse response = service.rename(director, sun.getId(), new RenameClassRequest("  해님반  "));

        assertEquals("해님반", response.name());
        assertEquals("해님반", sun.getName());
        assertEquals("SUN-CODE", response.joinCode());
    }

    @Test
    void renameRejectsBlankNameAndTooLongName() {
        ApiException blank = assertThrows(ApiException.class,
                () -> service.rename(director, sun.getId(), new RenameClassRequest("   ")));
        assertEquals(400, blank.statusCode());
        assertEquals("반 이름을 입력해 주세요.", blank.safeDetail());
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.rename(director, sun.getId(), new RenameClassRequest("가".repeat(256)))).statusCode());
        assertEquals("햇님반", sun.getName());
    }

    @Test
    void renameOfArchivedClassIsAllowed() {
        sun.setArchivedAt(Instant.now());
        assertEquals("지난 햇님반", service.rename(director, sun.getId(), new RenameClassRequest("지난 햇님반")).name());
    }

    @Test
    void otherDirectorIsForbiddenEverywhere() {
        CurrentUser other = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID());
        when(classService.requireOwnedByDirector(eq(other), any()))
                .thenThrow(ApiException.contractError(ErrorCode.FORBIDDEN, "이 반에 접근할 권한이 없어요.", 403));

        assertEquals(403, assertThrows(ApiException.class,
                () -> service.rename(other, sun.getId(), new RenameClassRequest("x"))).statusCode());
        assertEquals(403, assertThrows(ApiException.class, () -> service.archive(other, sun.getId())).statusCode());
        assertEquals(403, assertThrows(ApiException.class, () -> service.unarchive(other, sun.getId())).statusCode());
        assertEquals(403, assertThrows(ApiException.class, () -> service.moveStudents(
                other, sun.getId(), new MoveStudentsRequest(List.of(UUID.randomUUID()), star.getId()))).statusCode());
        assertEquals(403, assertThrows(ApiException.class, () -> service.termTransition(
                other, sun.getId(), new TermTransitionRequest(List.of(), true))).statusCode());
    }

    /* ---------------------------------------------------------- archive */

    @Test
    void archiveWithActiveStudentsIs409AndChangesNothing() {
        activeIn(sun, student(sun, kim, null));

        ApiException error = assertThrows(ApiException.class, () -> service.archive(director, sun.getId()));

        assertEquals(409, error.statusCode());
        assertEquals(ErrorCode.CLASS_HAS_ACTIVE_STUDENTS, error.code());
        assertNull(sun.getArchivedAt());
        verify(inviteRepository, never()).revokeLiveByClassGroupId(any(), any());
    }

    @Test
    void archiveEmptyClassStampsAndRevokesHomeroomInvites() {
        activeIn(sun);

        ClassResponse response = service.archive(director, sun.getId());

        assertNotNull(response.archivedAt());
        assertEquals(director.userId(), sun.getArchivedBy().getId());
        assertSame(kim, sun.getTutor(), "담임은 그대로 둔다 - 꺼내면 그대로 다시 쓴다");
        verify(inviteRepository).revokeLiveByClassGroupId(eq(sun.getId()), any());
    }

    @Test
    void archiveAndUnarchiveAreIdempotent() {
        Instant earlier = Instant.parse("2026-03-01T00:00:00Z");
        sun.setArchivedAt(earlier);

        assertEquals(earlier, service.archive(director, sun.getId()).archivedAt());
        verify(classGroupRepository, never()).save(any());

        assertNull(service.unarchive(director, sun.getId()).archivedAt());
        assertNull(sun.getArchivedBy());
        assertNull(service.unarchive(director, sun.getId()).archivedAt());
    }

    /* ---------------------------------------------------------- move */

    @Test
    void moveKeepsTheSameRowAndSwitchesClassTutorLessonsAndHistory() {
        TutorStudent minseo = student(sun, kim, child());
        minseo.setLinkedParentUser(parent);
        Lesson oldScheduled = lesson(kim, sun, LessonStatus.SCHEDULED, minseo);
        Lesson newScheduled = lesson(lee, star, LessonStatus.SCHEDULED);
        Lesson newInProgress = lesson(lee, star, LessonStatus.IN_PROGRESS);
        Lesson oldInProgress = lesson(kim, sun, LessonStatus.IN_PROGRESS, minseo);
        when(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(kim.getId(), sun.getId(), LessonStatus.SCHEDULED))
                .thenReturn(List.of(oldScheduled));
        when(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(kim.getId(), sun.getId(), LessonStatus.IN_PROGRESS))
                .thenReturn(List.of(oldInProgress));
        when(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(lee.getId(), star.getId(), LessonStatus.SCHEDULED))
                .thenReturn(List.of(newScheduled));
        when(lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(lee.getId(), star.getId(), LessonStatus.IN_PROGRESS))
                .thenReturn(List.of(newInProgress));

        MoveStudentsResponse response = service.moveStudents(
                director, sun.getId(), new MoveStudentsRequest(List.of(minseo.getId()), star.getId()));

        assertEquals(List.of(minseo.getId()), response.moved());
        assertTrue(response.skipped().isEmpty());
        assertSame(star, minseo.getClassGroup());
        assertSame(lee, minseo.getTutor());
        assertSame(parent, minseo.getLinkedParentUser(), "학부모 연결은 그대로");
        assertEquals(TutorStudentStatus.CONFIRMED, minseo.getStatus());
        assertTrue(oldScheduled.getStudents().isEmpty());
        assertTrue(oldInProgress.getStudents().isEmpty(), "떠난 뒤 저장한 지난 반 기록에 남지 않게 진행 중 수업에서도 뺀다");
        assertTrue(newScheduled.getStudents().contains(minseo));
        assertTrue(newInProgress.getStudents().contains(minseo));
        verify(classHistoryService).transfer(eq(minseo), eq(star), eq(ClassMembershipReason.MOVED),
                eq(ClassMembershipReason.MOVED), any(), eq(director.userId()));
    }

    @Test
    void moveNotifiesParentAndNewHomeroomWithHistoryDedupKey() {
        TutorStudent minseo = student(sun, kim, child());
        minseo.setName("민서");
        minseo.setLinkedParentUser(parent);

        service.moveStudents(director, sun.getId(), new MoveStudentsRequest(List.of(minseo.getId()), star.getId()));

        String key = String.valueOf(historyIds.get());
        verify(notificationPublisher).publish(parent.getId(), "class-student-moved", "민서가 별님반으로 옮겼어요",
                "햇님반에서 함께한 수업 리포트도 그대로 볼 수 있어요.", "/reports", "class-student-moved:" + key);
        verify(notificationPublisher).publish(eq(lee.getId()), eq("class-student-moved-in"), eq("민서가 별님반에 들어왔어요"),
                anyString(), eq("/tutor/students/" + minseo.getId()), eq("class-student-moved-in:" + key));
    }

    @Test
    void moveIntoClassWithoutHomeroomLeavesTutorEmptyAndNotifiesOnlyParent() {
        star.setTutor(null);
        TutorStudent minseo = student(sun, kim, null);
        minseo.setLinkedParentUser(parent);

        service.moveStudents(director, sun.getId(), new MoveStudentsRequest(List.of(minseo.getId()), star.getId()));

        assertNull(minseo.getTutor());
        verify(notificationPublisher).publish(eq(parent.getId()), eq("class-student-moved"), anyString(), anyString(),
                anyString(), anyString());
        verify(notificationPublisher, never()).publish(any(), eq("class-student-moved-in"), any(), any(), any(), any());
    }

    @Test
    void moveSkipsWithReasons() {
        TutorStudent elsewhere = student(star, lee, null);
        TutorStudent graduated = student(sun, null, null);
        graduated.setGraduatedAt(Instant.now());
        TutorStudent dupInTarget = student(sun, kim, child());
        when(tutorStudentRepository.existsByClassGroup_IdAndChild_IdAndDeletedAtIsNull(star.getId(), dupInTarget.getChild().getId()))
                .thenReturn(true);
        TutorStudent dupTutor = student(sun, kim, child());
        when(tutorStudentRepository.existsByTutor_IdAndChild_IdAndDeletedAtIsNullAndIdNot(
                lee.getId(), dupTutor.getChild().getId(), dupTutor.getId())).thenReturn(true);
        TutorStudent deleted = student(sun, kim, null);
        deleted.setDeletedAt(Instant.now());
        UUID missing = UUID.randomUUID();

        MoveStudentsResponse response = service.moveStudents(director, sun.getId(), new MoveStudentsRequest(
                List.of(elsewhere.getId(), graduated.getId(), dupInTarget.getId(), dupTutor.getId(), deleted.getId(), missing),
                star.getId()));

        assertTrue(response.moved().isEmpty());
        assertEquals(List.of(
                new SkippedStudent(elsewhere.getId(), SkippedStudent.NOT_IN_CLASS),
                new SkippedStudent(graduated.getId(), SkippedStudent.GRADUATED),
                new SkippedStudent(dupInTarget.getId(), SkippedStudent.ALREADY_IN_TARGET),
                new SkippedStudent(dupTutor.getId(), SkippedStudent.ALREADY_TUTOR_STUDENT),
                new SkippedStudent(deleted.getId(), SkippedStudent.NOT_IN_CLASS),
                new SkippedStudent(missing, SkippedStudent.NOT_IN_CLASS)), response.skipped());
        assertSame(sun, dupInTarget.getClassGroup());
        verify(classHistoryService, never()).transfer(any(), any(), any(), any(), any(), any());
        verify(notificationPublisher, never()).publish(any(), any(), any(), any(), any(), any());
    }

    @Test
    void moveToSameClassIsSkipped() {
        TutorStudent minseo = student(sun, kim, null);

        MoveStudentsResponse response = service.moveStudents(
                director, sun.getId(), new MoveStudentsRequest(List.of(minseo.getId()), sun.getId()));

        assertEquals(List.of(new SkippedStudent(minseo.getId(), SkippedStudent.SAME_CLASS)), response.skipped());
    }

    @Test
    void moveTargetInOtherOrganizationIs404AndArchivedTargetIs409() {
        ClassGroup foreign = ClassGroup.builder().id(UUID.randomUUID()).name("남의반")
                .organization(Organization.builder().id(UUID.randomUUID()).build()).build();
        when(classGroupRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        TutorStudent minseo = student(sun, kim, null);

        assertEquals(404, assertThrows(ApiException.class, () -> service.moveStudents(
                director, sun.getId(), new MoveStudentsRequest(List.of(minseo.getId()), foreign.getId()))).statusCode());

        star.setArchivedAt(Instant.now());
        ApiException archived = assertThrows(ApiException.class, () -> service.moveStudents(
                director, sun.getId(), new MoveStudentsRequest(List.of(minseo.getId()), star.getId())));
        assertEquals(409, archived.statusCode());
        assertEquals(ErrorCode.CLASS_ARCHIVED, archived.code());
        assertSame(sun, minseo.getClassGroup());
    }

    @Test
    void moveNeedsStudents() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.moveStudents(
                director, sun.getId(), new MoveStudentsRequest(List.of(), star.getId()))).statusCode());
    }

    /* ---------------------------------------------------------- term transition */

    @Test
    void termTransitionAppliesEveryDecisionAndArchives() {
        TutorStudent a = student(sun, kim, null);
        TutorStudent b = student(sun, kim, null);
        b.setName("하준");
        b.setLinkedParentUser(parent);
        activeIn(sun, a, b);

        TermTransitionResponse response = service.termTransition(director, sun.getId(), new TermTransitionRequest(
                List.of(new Decision(a.getId(), "MOVE", star.getId()), new Decision(b.getId(), "graduate", null)), true));

        assertEquals(List.of(a.getId()), response.moved());
        assertEquals(List.of(b.getId()), response.graduated());
        assertTrue(response.kept().isEmpty());
        assertTrue(response.archived());
        assertSame(star, a.getClassGroup());
        assertNotNull(b.getGraduatedAt());
        assertNull(b.getTutor(), "졸업하면 담당 선생님이 빈다");
        assertSame(sun, b.getClassGroup(), "졸업해도 반은 남는다(지난 반에서 보인다)");
        assertSame(parent, b.getLinkedParentUser(), "학부모 연결과 열람은 남는다");
        assertNotNull(sun.getArchivedAt());
        verify(classHistoryService).close(eq(b), eq(sun), eq(ClassMembershipReason.GRADUATED), any(), eq(director.userId()));
        verify(notificationPublisher).publish(eq(parent.getId()), eq("class-student-graduated"),
                eq("하준이 햇님반 수업을 마쳤어요"), eq("하준의 햇님반 수업 기록은 계속 볼 수 있어요."), eq("/reports"),
                anyString());
    }

    @Test
    void termTransitionKeepRecordsATermBoundaryWithoutArchiving() {
        TutorStudent a = student(sun, kim, null);
        activeIn(sun, a);

        TermTransitionResponse response = service.termTransition(director, sun.getId(),
                new TermTransitionRequest(List.of(new Decision(a.getId(), "KEEP", null)), false));

        assertEquals(List.of(a.getId()), response.kept());
        assertFalse(response.archived());
        assertSame(sun, a.getClassGroup());
        verify(classHistoryService).transfer(eq(a), eq(sun), eq(ClassMembershipReason.KEPT), eq(ClassMembershipReason.KEPT),
                any(), eq(director.userId()));
        assertNull(sun.getArchivedAt());
    }

    @Test
    void termTransitionNeedsADecisionForEveryActiveStudent() {
        TutorStudent a = student(sun, kim, null);
        TutorStudent b = student(sun, kim, null);
        activeIn(sun, a, b);

        ApiException error = assertThrows(ApiException.class, () -> service.termTransition(director, sun.getId(),
                new TermTransitionRequest(List.of(new Decision(a.getId(), "GRADUATE", null)), false)));

        assertEquals(400, error.statusCode());
        assertEquals("이 반 학생 모두의 다음 학기를 정해 주세요.", error.safeDetail());
        assertNull(a.getGraduatedAt(), "검증에 걸리면 아무것도 바꾸지 않는다");
    }

    @Test
    void termTransitionRejectsBadDecisionsBeforeChangingAnything() {
        TutorStudent a = student(sun, kim, null);
        activeIn(sun, a);
        UUID stranger = UUID.randomUUID();

        List<TermTransitionRequest> bad = List.of(
                new TermTransitionRequest(List.of(new Decision(stranger, "GRADUATE", null)), false),
                new TermTransitionRequest(List.of(new Decision(a.getId(), "GRADUATE", null),
                        new Decision(a.getId(), "KEEP", null)), false),
                new TermTransitionRequest(List.of(new Decision(a.getId(), "LEAVE", null)), false),
                new TermTransitionRequest(List.of(new Decision(a.getId(), "MOVE", null)), false),
                new TermTransitionRequest(List.of(new Decision(a.getId(), "MOVE", sun.getId())), false),
                new TermTransitionRequest(List.of(new Decision(a.getId(), "KEEP", null)), true));
        for (TermTransitionRequest request : bad) {
            assertEquals(400, assertThrows(ApiException.class,
                    () -> service.termTransition(director, sun.getId(), request)).statusCode());
        }
        assertNull(a.getGraduatedAt());
        assertNull(sun.getArchivedAt());
        verify(classHistoryService, never()).transfer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void termTransitionWithSkippedMoveAndArchiveIs409() {
        TutorStudent a = student(sun, kim, child());
        activeIn(sun, a);
        when(tutorStudentRepository.existsByClassGroup_IdAndChild_IdAndDeletedAtIsNull(star.getId(), a.getChild().getId()))
                .thenReturn(true);

        ApiException error = assertThrows(ApiException.class, () -> service.termTransition(director, sun.getId(),
                new TermTransitionRequest(List.of(new Decision(a.getId(), "MOVE", star.getId())), true)));

        assertEquals(409, error.statusCode());
        assertEquals(ErrorCode.CLASS_HAS_ACTIVE_STUDENTS, error.code());
        assertNull(sun.getArchivedAt());
    }

    @Test
    void termTransitionOfEmptyClassJustArchives() {
        activeIn(sun);

        TermTransitionResponse response = service.termTransition(director, sun.getId(), new TermTransitionRequest(null, true));

        assertTrue(response.archived());
        assertNotNull(sun.getArchivedAt());
    }

    /* ---------------------------------------------------------- class history */

    @Test
    void classHistoryForDirectorCurrentAndFormerHomeroomOnly() {
        TutorStudent minseo = student(star, lee, null);
        TutorStudentClassHistory first = TutorStudentClassHistory.builder().id(1L).tutorStudent(minseo).classGroup(sun)
                .startedAt(Instant.parse("2026-03-01T00:00:00Z")).endedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .reason(ClassMembershipReason.JOINED).endReason(ClassMembershipReason.MOVED).build();
        TutorStudentClassHistory second = TutorStudentClassHistory.builder().id(2L).tutorStudent(minseo).classGroup(star)
                .startedAt(Instant.parse("2026-09-01T00:00:00Z")).reason(ClassMembershipReason.MOVED).build();
        when(classHistoryService.list(minseo.getId())).thenReturn(List.of(first, second));

        List<ClassHistoryEntryResponse> forDirector = service.classHistory(director, minseo.getId());
        assertEquals(2, forDirector.size());
        assertEquals("햇님반", forDirector.get(0).className());
        assertEquals("MOVED", forDirector.get(0).endReason());
        assertNull(forDirector.get(1).endedAt());

        // 지금 담임
        assertEquals(2, service.classHistory(new CurrentUser(lee.getId(), Role.TUTOR, null), minseo.getId()).size());

        // 지난 반(햇님반)의 담임이었고 아직 기관 소속
        CurrentUser kimCaller = new CurrentUser(kim.getId(), Role.TUTOR, null);
        when(homeroomHistoryService.hasLed(sun.getId(), kim.getId())).thenReturn(true);
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), kim.getId()))
                .thenReturn(Optional.of(OrganizationTutor.builder().organization(organization).tutor(kim).build()));
        assertEquals(2, service.classHistory(kimCaller, minseo.getId()).size());

        // 기관을 떠났으면 403
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), kim.getId()))
                .thenReturn(Optional.empty());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.classHistory(kimCaller, minseo.getId())).statusCode());

        // 다른 기관 원장, 학부모
        assertEquals(403, assertThrows(ApiException.class, () -> service.classHistory(
                new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID()), minseo.getId())).statusCode());
        assertEquals(403, assertThrows(ApiException.class, () -> service.classHistory(
                new CurrentUser(parent.getId(), Role.PARENT, null), minseo.getId())).statusCode());
    }

    @Test
    void classHistoryOfUnknownStudentIs404() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.classHistory(director, UUID.randomUUID())).statusCode());
    }

    // ---- helpers

    private static AppUser tutor(String name) {
        return AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName(name).build();
    }

    private ClassGroup classGroup(String name, AppUser homeroom) {
        ClassGroup classGroup = ClassGroup.builder().id(UUID.randomUUID()).organization(organization).tutor(homeroom)
                .name(name).joinCode(name.equals("햇님반") ? "SUN-CODE" : "STAR-CODE").createdAt(Instant.now()).build();
        when(classGroupRepository.findById(classGroup.getId())).thenReturn(Optional.of(classGroup));
        return classGroup;
    }

    private TutorStudent student(ClassGroup classGroup, AppUser tutor, Child child) {
        TutorStudent student = TutorStudent.builder().id(UUID.randomUUID()).tutor(tutor).classGroup(classGroup)
                .child(child).name("민서").ageBand("5세").status(TutorStudentStatus.CONFIRMED)
                .createdAt(Instant.now()).build();
        when(tutorStudentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        return student;
    }

    private void activeIn(ClassGroup classGroup, TutorStudent... students) {
        when(tutorStudentRepository.findByClassGroup_IdAndDeletedAtIsNullOrderByCreatedAtAsc(classGroup.getId()))
                .thenReturn(List.of(students));
    }

    private static Child child() {
        return Child.builder().id(UUID.randomUUID()).build();
    }

    private Lesson lesson(AppUser tutor, ClassGroup classGroup, LessonStatus status, TutorStudent... students) {
        return Lesson.builder().id(UUID.randomUUID()).tutor(tutor).classGroup(classGroup).status(status)
                .name("수업").students(new LinkedHashSet<>(new ArrayList<>(List.of(students))))
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    private TutorStudentClassHistory history(TutorStudent student, ClassGroup classGroup) {
        return TutorStudentClassHistory.builder().id(historyIds.incrementAndGet()).tutorStudent(student)
                .classGroup(classGroup).startedAt(Instant.now()).reason(ClassMembershipReason.MOVED).build();
    }
}
