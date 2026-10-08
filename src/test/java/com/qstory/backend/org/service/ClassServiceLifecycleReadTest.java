package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.ConsentService;
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.identity.util.AuthValidator;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.dto.ClassMembershipResponse;
import com.qstory.backend.org.dto.ClassReportResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.ClassStudentReportResponse;
import com.qstory.backend.org.dto.ClassStudentResponse;
import com.qstory.backend.org.dto.JoinExistingClassRequest;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassMembershipReason;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.entity.TutorStudentClassHistory;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import com.qstory.backend.tutor.service.TutorStudentService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 076 읽기 규칙: 지난 반의 반 코드는 410, 원장 목록은 기본으로 지난 반을 뺀다, 졸업생은 지금 명단에서 빠지고
 * includePast로 지난 학생을 본다, 원장은 기관 학생이면 반이 달라도 전부, 새 담임은 옮겨 온 학생의 지난 반 기록까지,
 * 지난 담임은 자기가 진행한 수업만.
 */
class ClassServiceLifecycleReadTest {

    private final ClassGroupRepository classGroupRepository = mock(ClassGroupRepository.class);
    private final TutorStudentRepository tutorStudentRepository = mock(TutorStudentRepository.class);
    private final OrganizationTutorRepository organizationTutorRepository = mock(OrganizationTutorRepository.class);
    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final StoryCompletionRepository completionRepository = mock(StoryCompletionRepository.class);
    private final ClassHomeroomHistoryService homeroomHistoryService = mock(ClassHomeroomHistoryService.class);
    private final StudentClassHistoryService classHistoryService = mock(StudentClassHistoryService.class);
    private final TutorStudentService tutorStudentService = mock(TutorStudentService.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final ClassService service = new ClassService(
            classGroupRepository, tutorStudentRepository, organizationTutorRepository, userRepository,
            organizationService, mock(JoinCodeGenerator.class), mock(AuthValidator.class),
            mock(PasswordEncoder.class), mock(JwtService.class), tutorStudentService,
            mock(UserSummaryFactory.class), completionRepository, mock(LessonRepository.class), homeroomHistoryService,
            mock(ConsentService.class), mock(NotificationPublisher.class), classHistoryService);

    private final Organization organization = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, organization.getId());
    private final AppUser kim = tutor("김선생");
    private final AppUser lee = tutor("이선생");
    private final CurrentUser kimCaller = new CurrentUser(kim.getId(), Role.TUTOR, null);
    private final CurrentUser leeCaller = new CurrentUser(lee.getId(), Role.TUTOR, null);
    private ClassGroup sun;
    private ClassGroup star;

    @BeforeEach
    void setUp() {
        sun = classGroup("햇님반", "SUN12345", kim);
        star = classGroup("별님반", "STAR1234", lee);
    }

    /* ---------------------------------------------------------- join code */

    @Test
    void archivedClassCodeIsGoneForPreviewRosterAndJoin() {
        sun.setArchivedAt(Instant.now());
        when(classGroupRepository.findByJoinCode("SUN12345")).thenReturn(Optional.of(sun));
        AppUser parent = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).build();
        when(userRepository.lockActiveById(parent.getId())).thenReturn(Optional.of(parent));

        for (Runnable call : List.<Runnable>of(
                () -> service.preview("sun12345"),
                () -> service.pendingRoster("SUN12345"),
                () -> service.joinExistingParent(new CurrentUser(parent.getId(), Role.PARENT, null),
                        new JoinExistingClassRequest("SUN12345", "민서", 2021, null, null)))) {
            ApiException error = assertThrows(ApiException.class, call::run);
            assertEquals(410, error.statusCode());
            assertEquals(ErrorCode.INVALID_INVITE, error.code());
            assertEquals("지난 반이라 더 이상 들어갈 수 없어요. 원장 선생님께 새 반 코드를 받아 주세요.", error.safeDetail());
        }
        verify(tutorStudentService, never()).enrollParentInClass(any(), any(), any(), any(), any(), any());
    }

    /* ---------------------------------------------------------- lists */

    @Test
    void directorListExcludesArchivedUnlessAsked() {
        star.setArchivedAt(Instant.now());
        when(classGroupRepository.findByOrganization_IdOrderByCreatedAtAsc(organization.getId())).thenReturn(List.of(sun, star));

        assertEquals(List.of(sun.getId()),
                service.list(director, organization.getId(), false).stream().map(ClassResponse::id).toList());
        List<ClassResponse> all = service.list(director, organization.getId(), true);
        assertEquals(2, all.size());
        assertEquals(star.getArchivedAt(), all.get(1).archivedAt());
    }

    @Test
    void assigningHomeroomToArchivedClassIs409() {
        sun.setArchivedAt(Instant.now());
        ApiException error = assertThrows(ApiException.class,
                () -> service.assignHomeroom(director, sun.getId(), lee.getId()));
        assertEquals(409, error.statusCode());
        assertEquals(ErrorCode.CLASS_ARCHIVED, error.code());
    }

    @Test
    void studentListShowsCurrentAndOptionallyPastMembers() {
        TutorStudent current = student("민서", sun, kim);
        TutorStudent movedAway = student("하준", star, lee);
        TutorStudent graduated = student("서윤", sun, null);
        graduated.setGraduatedAt(Instant.parse("2027-02-28T00:00:00Z"));
        when(tutorStudentRepository.findByClassGroup_IdAndDeletedAtIsNullOrderByCreatedAtAsc(sun.getId()))
                .thenReturn(List.of(current));
        when(classHistoryService.endedInClass(sun.getId())).thenReturn(List.of(
                ended(graduated, sun, ClassMembershipReason.GRADUATED, graduated.getGraduatedAt()),
                ended(movedAway, sun, ClassMembershipReason.MOVED, Instant.parse("2026-09-01T00:00:00Z")),
                // 학기를 넘겨 같은 반에 남은 학생의 지난 구간 - 지금 학생이라 지난 학생으로 다시 나오지 않는다.
                ended(current, sun, ClassMembershipReason.KEPT, Instant.parse("2026-03-01T00:00:00Z"))));

        assertEquals(List.of("민서"), names(service.listStudents(director, sun.getId(), false)));

        List<ClassStudentResponse> withPast = service.listStudents(director, sun.getId(), true);
        assertEquals(List.of("민서", "서윤", "하준"), names(withPast));
        assertNull(withPast.get(0).endedAt());
        assertEquals("GRADUATED", withPast.get(1).endReason());
        assertEquals(graduated.getGraduatedAt(), withPast.get(1).graduatedAt());
        assertEquals("MOVED", withPast.get(2).endReason());
        assertNull(withPast.get(2).graduatedAt());
    }

    @Test
    void parentMembershipsHideGraduatedChildren() {
        TutorStudent current = student("민서", sun, kim);
        TutorStudent graduated = student("서윤", star, null);
        graduated.setGraduatedAt(Instant.now());
        UUID parentId = UUID.randomUUID();
        when(tutorStudentRepository.findByLinkedParentUser_IdAndDeletedAtIsNullOrderByCreatedAtAsc(parentId))
                .thenReturn(List.of(current, graduated));

        List<ClassMembershipResponse> memberships = service.listMemberships(new CurrentUser(parentId, Role.PARENT, null));
        assertEquals(1, memberships.size());
    }

    /* ---------------------------------------------------------- student reports */

    @Test
    void directorSeesStudentOfAnyClassInTheOrganizationWithAllRecords() {
        TutorStudent minseo = student("민서", star, lee);
        StoryCompletion inSun = completion(kim, sun, "햇님반(그때)");
        StoryCompletion inStar = completion(lee, star, null);
        when(completionRepository.findByParticipant(minseo.getId())).thenReturn(List.of(inStar, inSun));

        // 지금은 별님반 학생인데 햇님반 화면에서 열어도 같은 기관이라 보인다.
        List<ClassStudentReportResponse> reports = service.listStudentReports(director, sun.getId(), minseo.getId());

        assertEquals(2, reports.size());
        assertEquals("별님반", reports.get(0).className(), "스냅샷이 없으면 지금 반 이름");
        assertEquals("햇님반(그때)", reports.get(1).className(), "그때 반 이름 스냅샷");
        assertEquals(sun.getId(), reports.get(1).classId());
    }

    @Test
    void directorCannotOpenStudentOfAnotherOrganization() {
        ClassGroup foreign = ClassGroup.builder().id(UUID.randomUUID()).name("남의반")
                .organization(Organization.builder().id(UUID.randomUUID()).build()).build();
        TutorStudent stranger = student("남", foreign, null);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.listStudentReports(director, sun.getId(), stranger.getId())).statusCode());
    }

    @Test
    void newHomeroomSeesMovedStudentsEarlierClassRecordsButNotSameClassPreviousHomeroom() {
        TutorStudent minseo = student("민서", star, lee);
        AppUser previousStarHomeroom = tutor("박선생");
        StoryCompletion oldClassGroup = completion(kim, sun, "햇님반");
        StoryCompletion own = completion(lee, star, "별님반");
        StoryCompletion sameClassPreviousHomeroom = completion(previousStarHomeroom, star, "별님반");
        StoryCompletion otherOrganization = completion(kim, ClassGroup.builder().id(UUID.randomUUID()).name("남의반")
                .organization(Organization.builder().id(UUID.randomUUID()).build()).build(), "남의반");
        otherOrganization.setOrganization(otherOrganization.getClassGroup().getOrganization());
        when(completionRepository.findByParticipant(minseo.getId()))
                .thenReturn(List.of(own, sameClassPreviousHomeroom, oldClassGroup, otherOrganization));

        List<UUID> ids = service.listStudentReports(leeCaller, star.getId(), minseo.getId()).stream()
                .map(ClassStudentReportResponse::id).toList();

        assertEquals(List.of(own.getId(), oldClassGroup.getId()), ids);
    }

    @Test
    void homeroomSeesOnlyOwnRecordsOfStudentWhoLeftTheClass() {
        // 별님반 담임이 햇님반으로 옮겨 간 학생을 별님반 화면에서 연다 - 자기가 진행한 기록만.
        TutorStudent movedAway = student("하준", sun, kim);
        StoryCompletion own = completion(lee, star, "별님반");
        StoryCompletion afterMove = completion(kim, sun, "햇님반");
        when(completionRepository.findByParticipant(movedAway.getId())).thenReturn(List.of(afterMove, own));
        when(classHistoryService.list(movedAway.getId())).thenReturn(List.of(
                ended(movedAway, star, ClassMembershipReason.MOVED, Instant.now())));

        List<UUID> ids = service.listStudentReports(leeCaller, star.getId(), movedAway.getId()).stream()
                .map(ClassStudentReportResponse::id).toList();
        assertEquals(List.of(own.getId()), ids);
    }

    @Test
    void homeroomCannotOpenStudentWhoWasNeverInTheClass() {
        TutorStudent other = student("남", sun, kim);
        when(classHistoryService.list(other.getId())).thenReturn(List.of());

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.listStudentReports(leeCaller, star.getId(), other.getId())).statusCode());
    }

    /* ---------------------------------------------------------- former homeroom */

    @Test
    void formerHomeroomStillInOrganizationSeesClassWithoutJoinCodeAndOnlyOwnSessions() {
        formerHomeroom(kim, star, true);
        StoryCompletion own = completion(kim, star, "별님반");
        when(completionRepository.findClassReportsByUser(eq(star.getId()), eq(kim.getId()), any())).thenReturn(List.of(own));

        ClassResponse detail = service.get(kimCaller, star.getId());
        assertNull(detail.joinCode());
        assertEquals("별님반", detail.name());

        List<ClassReportResponse> reports = service.listClassReports(kimCaller, star.getId(), null);
        assertEquals(List.of(own.getId()), reports.stream().map(ClassReportResponse::id).toList());
        verify(completionRepository, never()).findClassReports(any(), any());
    }

    @Test
    void formerHomeroomSeesOnlyStudentsTheyTaughtAndOnlyTheirOwnRecords() {
        formerHomeroom(kim, star, true);
        TutorStudent taught = student("민서", star, lee);
        TutorStudent newcomer = student("새친구", star, lee);
        when(tutorStudentRepository.findByClassGroup_IdAndDeletedAtIsNullOrderByCreatedAtAsc(star.getId()))
                .thenReturn(List.of(taught, newcomer));
        when(completionRepository.findParticipantIdsOfClassSessionsByUser(star.getId(), kim.getId()))
                .thenReturn(List.of(taught.getId()));
        StoryCompletion own = completion(kim, star, "별님반");
        StoryCompletion currentHomeroom = completion(lee, star, "별님반");
        when(completionRepository.findByParticipant(taught.getId())).thenReturn(List.of(currentHomeroom, own));

        assertEquals(List.of("민서"), names(service.listStudents(kimCaller, star.getId(), true)));
        assertEquals(List.of(own.getId()), service.listStudentReports(kimCaller, star.getId(), taught.getId()).stream()
                .map(ClassStudentReportResponse::id).toList());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.listStudentReports(kimCaller, star.getId(), newcomer.getId())).statusCode());
    }

    @Test
    void formerHomeroomWhoLeftTheOrganizationIsForbidden() {
        formerHomeroom(kim, star, false);

        assertEquals(403, assertThrows(ApiException.class, () -> service.get(kimCaller, star.getId())).statusCode());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.listClassReports(kimCaller, star.getId(), null)).statusCode());
    }

    @Test
    void tutorWhoNeverLedTheClassIsForbidden() {
        CurrentUser stranger = new CurrentUser(UUID.randomUUID(), Role.TUTOR, null);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.listStudents(stranger, star.getId(), false)).statusCode());
    }

    // ---- helpers

    private static AppUser tutor(String name) {
        return AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName(name).build();
    }

    private ClassGroup classGroup(String name, String code, AppUser homeroom) {
        ClassGroup classGroup = ClassGroup.builder().id(UUID.randomUUID()).organization(organization).tutor(homeroom)
                .name(name).joinCode(code).createdAt(Instant.now()).build();
        when(classGroupRepository.findById(classGroup.getId())).thenReturn(Optional.of(classGroup));
        return classGroup;
    }

    private TutorStudent student(String name, ClassGroup classGroup, AppUser tutor) {
        TutorStudent student = TutorStudent.builder().id(UUID.randomUUID()).tutor(tutor).classGroup(classGroup)
                .name(name).ageBand("5세").status(TutorStudentStatus.CONFIRMED).createdAt(Instant.now()).build();
        when(tutorStudentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        return student;
    }

    private StoryCompletion completion(AppUser tutor, ClassGroup classGroup, String className) {
        return StoryCompletion.builder().id(UUID.randomUUID()).user(tutor).organization(classGroup.getOrganization())
                .classGroup(classGroup).className(className).groupSession(true).storyId("HG")
                .completedAt(Instant.now()).build();
    }

    private static TutorStudentClassHistory ended(
            TutorStudent student, ClassGroup classGroup, String endReason, Instant endedAt) {
        return TutorStudentClassHistory.builder().id(1L).tutorStudent(student).classGroup(classGroup)
                .startedAt(Instant.parse("2026-01-01T00:00:00Z")).endedAt(endedAt)
                .reason(ClassMembershipReason.JOINED).endReason(endReason).build();
    }

    private void formerHomeroom(AppUser tutor, ClassGroup classGroup, boolean stillInOrganization) {
        when(homeroomHistoryService.hasLed(classGroup.getId(), tutor.getId())).thenReturn(true);
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), tutor.getId()))
                .thenReturn(stillInOrganization
                        ? Optional.of(OrganizationTutor.builder().organization(organization).tutor(tutor).build())
                        : Optional.empty());
    }

    private static List<String> names(List<ClassStudentResponse> students) {
        return students.stream().map(ClassStudentResponse::name).toList();
    }
}
