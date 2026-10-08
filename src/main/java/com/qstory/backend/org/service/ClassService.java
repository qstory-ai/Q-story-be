package com.qstory.backend.org.service;

import com.qstory.backend.common.util.TeacherName;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
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
import com.qstory.backend.org.dto.ClassPreviewResponse;
import com.qstory.backend.org.dto.ClassRosterEntryResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.ClassStudentReportResponse;
import com.qstory.backend.org.dto.ClassStudentResponse;
import com.qstory.backend.org.dto.HomeroomHistoryEntryResponse;
import com.qstory.backend.org.dto.CreateClassRequest;
import com.qstory.backend.org.dto.JoinClassRequest;
import com.qstory.backend.org.dto.JoinExistingClassRequest;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import com.qstory.backend.tutor.service.TutorStudentService;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관 반 - 기관 → 담임 선생님 → 학생 명단. 원장은 반을 만들고 담임을 배정하고 명단을 볼 뿐 수업은
 * 담임의 계정으로 진행하며, 학부모는 반 코드로 자기 아이를 그 반의 학생으로 올린다(부모 계정에는 반
 * 소속을 남기지 않는다). 그래서 기관 반의 수업 기록도 튜터 수업과 같은 경로로 부모에게 전달된다.
 */
@Service
public class ClassService {

    private final ClassGroupRepository classGroupRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final OrganizationTutorRepository organizationTutorRepository;
    private final AppUserRepository userRepository;
    private final OrganizationService organizationService;
    private final JoinCodeGenerator joinCodeGenerator;
    private final AuthValidator authValidator;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TutorStudentService tutorStudentService;
    private final UserSummaryFactory userSummaryFactory;
    private final StoryCompletionRepository storyCompletionRepository;
    private final LessonRepository lessonRepository;
    private final ClassHomeroomHistoryService homeroomHistoryService;
    private final ConsentService consentService;
    private final NotificationPublisher notificationPublisher;

    public ClassService(
            ClassGroupRepository classGroupRepository, TutorStudentRepository tutorStudentRepository,
            OrganizationTutorRepository organizationTutorRepository, AppUserRepository userRepository,
            OrganizationService organizationService, JoinCodeGenerator joinCodeGenerator,
            AuthValidator authValidator, PasswordEncoder passwordEncoder, JwtService jwtService,
            TutorStudentService tutorStudentService, UserSummaryFactory userSummaryFactory,
            StoryCompletionRepository storyCompletionRepository, LessonRepository lessonRepository,
            ClassHomeroomHistoryService homeroomHistoryService, ConsentService consentService,
            NotificationPublisher notificationPublisher) {
        this.consentService = consentService;
        this.notificationPublisher = notificationPublisher;
        this.classGroupRepository = classGroupRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.organizationTutorRepository = organizationTutorRepository;
        this.userRepository = userRepository;
        this.organizationService = organizationService;
        this.joinCodeGenerator = joinCodeGenerator;
        this.authValidator = authValidator;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tutorStudentService = tutorStudentService;
        this.userSummaryFactory = userSummaryFactory;
        this.storyCompletionRepository = storyCompletionRepository;
        this.lessonRepository = lessonRepository;
        this.homeroomHistoryService = homeroomHistoryService;
    }

    @Transactional
    public ClassResponse create(CurrentUser caller, UUID organizationId, CreateClassRequest request) {
        Organization organization = organizationService.requireOwned(caller, organizationId);
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "반 이름을 입력해 주세요.");
        }
        AppUser homeroom = request.homeroomTutorId() == null
                ? null : requireOrganizationTutor(organizationId, request.homeroomTutorId());
        ClassGroup classGroup = classGroupRepository.save(ClassGroup.builder()
                .organization(organization)
                .tutor(homeroom)
                .name(request.name().trim())
                .joinCode(generateUniqueJoinCode())
                .createdAt(Instant.now())
                .build());
        if (homeroom != null) {
            homeroomHistoryService.start(classGroup, homeroom, classGroup.getCreatedAt());
        }
        return ClassResponse.of(classGroup);
    }

    public List<ClassResponse> list(CurrentUser caller, UUID organizationId) {
        organizationService.requireOwned(caller, organizationId);
        return classGroupRepository.findByOrganization_IdOrderByCreatedAtAsc(organizationId).stream()
                .map(ClassResponse::of)
                .toList();
    }

    public ClassResponse get(CurrentUser caller, UUID classId) {
        return ClassResponse.of(requireVisible(caller, classId));
    }

    /** 반 상세의 학생 명단 - 담임이 아직 없는 반의 학생과 학부모가 아직 연결되지 않은 학생도 포함한다. */
    @Transactional(readOnly = true)
    public List<ClassStudentResponse> listStudents(CurrentUser caller, UUID classId) {
        requireVisible(caller, classId);
        return tutorStudentRepository.findByClassGroup_IdAndDeletedAtIsNullOrderByCreatedAtAsc(classId).stream()
                .map(ClassStudentResponse::of)
                .toList();
    }

    /**
     * 담임을 배정하거나 바꾼다(Q-35). 원장만, 그리고 기관에 소속된 선생님으로만.
     *
     * <p>담임 변경 정책: 지난 수업과 리포트는 그때 진행한 선생님 것으로 남는다 - lesson.tutor_id와
     * story_completion.user_id는 건드리지 않는다. 이 반의 학생(명단)은 새 담임에게 넘어가고, 아직 시작하지 않은
     * 예정 수업(SCHEDULED)도 새 담임에게 넘어간다. 진행 중·완료된 수업은 이전 담임에게 남는다. 새 담임은 학생
     * 상세에서 자기가 진행한 기록만 보고(TutorReportService), 원장은 두 선생님의 기록을 모두 본다.
     *
     * <p>새 담임이 같은 아이를 다른 학생 등록으로 이미 갖고 있으면((tutor_id, child_id) 유니크) 그 학생만 담임 없이
     * 명단에 남긴다 - 배정 자체는 막지 않고, 명단과 이용권은 그대로다.
     */
    @Transactional
    public ClassResponse assignHomeroom(CurrentUser caller, UUID classId, UUID tutorId) {
        ClassGroup classGroup = requireOwnedByDirector(caller, classId);
        if (tutorId == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "담임으로 배정할 선생님을 골라 주세요.");
        }
        AppUser previous = classGroup.getTutor();
        if (previous != null && previous.getId().equals(tutorId)) {
            return ClassResponse.of(classGroup);
        }
        AppUser homeroom = requireOrganizationTutor(classGroup.getOrganization().getId(), tutorId);
        // 원장이 직접 배정한 경우의 알림 중복 방지 키 - 배정 시각(같은 배정이 재시도로 두 번 발행돼도 하나만 남는다).
        return applyHomeroom(classGroup, homeroom, "assign-" + Instant.now().toEpochMilli());
    }

    /**
     * 담임 배정·변경의 본체 - 원장의 배정(assignHomeroom)과 담임 초대 수락(ClassHomeroomInviteService)이 같이 쓴다.
     * 정책은 assignHomeroom 설명 그대로다(담임 이력, 명단 학생과 예정 수업 이관). 권한 확인과 "기관 소속 선생님인지"
     * 확인은 호출하는 쪽이 먼저 한다. 이미 그 선생님이 담임이면 아무것도 바꾸지 않는다(알림도 없다).
     *
     * <p>새 담임에게 "담임이 됐어요"(homeroom-assigned), 이전 담임이 있으면 그 선생님에게 "담임이 바뀌었어요"
     * (homeroom-changed)를 보낸다. 원장 알림은 호출하는 쪽 몫이다 - 원장이 직접 배정했으면 보내지 않는다.
     * eventKey는 알림 중복 방지 키의 끝부분(담임 초대 id 또는 배정 시각)이다.
     */
    @Transactional
    public ClassResponse applyHomeroom(ClassGroup classGroup, AppUser homeroom, String eventKey) {
        UUID classId = classGroup.getId();
        AppUser previous = classGroup.getTutor();
        if (previous != null && previous.getId().equals(homeroom.getId())) {
            return ClassResponse.of(classGroup);
        }
        Instant now = Instant.now();
        classGroup.setTutor(homeroom);
        classGroupRepository.save(classGroup);
        homeroomHistoryService.start(classGroup, homeroom, now);

        // 담임 미정일 때 들어온 학생과(배정), 이전 담임의 학생(변경)을 새 담임에게 넘긴다.
        List<TutorStudent> candidates = new java.util.ArrayList<>(
                tutorStudentRepository.findByClassGroup_IdAndTutorIsNullAndDeletedAtIsNull(classId));
        if (previous != null) {
            candidates.addAll(tutorStudentRepository
                    .findByClassGroup_IdAndTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(classId, previous.getId()));
        }
        List<TutorStudent> moved = new java.util.ArrayList<>();
        for (TutorStudent student : candidates) {
            boolean duplicateChild = student.getChild() != null
                    && tutorStudentRepository.existsByTutor_IdAndChild_IdAndDeletedAtIsNull(homeroom.getId(), student.getChild().getId());
            student.setTutor(duplicateChild ? null : homeroom);
            if (!duplicateChild) moved.add(student);
        }
        tutorStudentRepository.saveAll(candidates);

        if (previous != null) {
            transferScheduledLessons(classGroup, previous, homeroom, moved, now);
        }
        notifyHomeroomChange(classGroup, previous, homeroom, eventKey);
        return ClassResponse.of(classGroup);
    }

    private void notifyHomeroomChange(ClassGroup classGroup, AppUser previous, AppUser homeroom, String eventKey) {
        String className = classGroup.getName();
        String orgName = classGroup.getOrganization() == null ? "" : classGroup.getOrganization().getName();
        notificationPublisher.publish(
                homeroom.getId(),
                "homeroom-assigned",
                NotificationText.title(className + " 담임이 됐어요"),
                NotificationText.body((orgName + " " + className).trim() + " 수업과 리포트를 이 계정에서 볼 수 있어요."),
                "/tutor/classes",
                "homeroom-assigned:" + classGroup.getId() + ":" + homeroom.getId() + ":" + eventKey);
        if (previous != null && previous.getDeletedAt() == null) {
            notificationPublisher.publish(
                    previous.getId(),
                    "homeroom-changed",
                    NotificationText.title(className + " 담임이 " + TeacherName.of(homeroom.getDisplayName()) + "으로 바뀌었어요"),
                    "지금까지 진행한 수업 기록은 그대로 남아 있어요.",
                    "/tutor/classes",
                    "homeroom-changed:" + classGroup.getId() + ":" + previous.getId() + ":" + eventKey);
        }
    }

    /** 이전 담임의 이 반 예정 수업을 새 담임에게 넘긴다. 참여 학생 중 새 담임에게 넘어가지 못한 학생은 뺀다. */
    private void transferScheduledLessons(
            ClassGroup classGroup, AppUser previous, AppUser homeroom, List<TutorStudent> moved, Instant now) {
        java.util.Set<UUID> movedIds = new java.util.HashSet<>();
        moved.forEach(student -> movedIds.add(student.getId()));
        List<Lesson> lessons = lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                previous.getId(), classGroup.getId(), LessonStatus.SCHEDULED);
        for (Lesson lesson : lessons) {
            lesson.setTutor(homeroom);
            lesson.getStudents().removeIf(student -> !movedIds.contains(student.getId()));
            lesson.setUpdatedAt(now);
        }
        lessonRepository.saveAll(lessons);
    }

    /** 반 담임 이력 - 원장만. 담임이 바뀐 반에서 누가 언제 맡았는지 본다. */
    @Transactional(readOnly = true)
    public List<HomeroomHistoryEntryResponse> homeroomHistory(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = requireOwnedByDirector(caller, classId);
        return homeroomHistoryService.list(classGroup).stream()
                .map(HomeroomHistoryEntryResponse::of)
                .toList();
    }

    /**
     * 반 학생 한 명의 수업 리포트 목록. 원장은 이 학생이 참여한 기록 전부(담임이 바뀌었으면 두 선생님 것 모두),
     * 담임 선생님은 자기가 진행한 기록만 본다 - 지난 담임의 기록은 지난 담임 것으로 남는다.
     */
    @Transactional(readOnly = true)
    public List<ClassStudentReportResponse> listStudentReports(CurrentUser caller, UUID classId, UUID studentId) {
        ClassGroup classGroup = requireVisible(caller, classId);
        TutorStudent student = tutorStudentRepository.findById(studentId)
                .filter(found -> found.getClassGroup() != null && found.getClassGroup().getId().equals(classGroup.getId()))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "학생을 찾을 수 없어요.", 404));
        boolean director = isOwningDirector(caller, classGroup);
        return storyCompletionRepository.findByParticipant(student.getId()).stream()
                .filter(completion -> director || completion.getUser().getId().equals(caller.userId()))
                .map(ClassStudentReportResponse::of)
                .toList();
    }

    /** 반의 최근 수업 리포트(가정 기록 제외, 최신순). 관리자는 모든 선생님 기록, 담임은 자기가 진행한 기록만. */
    @Transactional(readOnly = true)
    public List<ClassReportResponse> listClassReports(CurrentUser caller, UUID classId, Integer limit) {
        ClassGroup classGroup = requireVisible(caller, classId);
        int size = limit == null || limit < 1 ? 20 : Math.min(limit, 50);
        PageRequest page = PageRequest.of(0, size);
        List<StoryCompletion> completions = isOwningDirector(caller, classGroup)
                ? storyCompletionRepository.findClassReports(classGroup.getId(), page)
                : storyCompletionRepository.findClassReportsByUser(classGroup.getId(), caller.userId(), page);
        return completions.stream().map(ClassReportResponse::of).toList();
    }

    /** 반 코드로 학부모 계정을 만들고 아이를 그 반의 학생으로 올린다 - 가입과 동시에 연결된다. */
    @Transactional
    public AuthResponse join(JoinClassRequest request) {
        ClassGroup classGroup = resolveClassGroup(request.classCode());
        authValidator.validateSignup(new SignupOrganizationOwnerRequest(
                request.loginId(), request.email(), request.password(), request.displayName(), request.consents()));
        consentService.requireSignupConsents(request.consents());

        AppUser parent = AppUser.builder()
                .role(Role.PARENT)
                .loginId(request.loginId().trim().toLowerCase())
                .email(request.email().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName().trim())
                .createdAt(Instant.now())
                .build();
        parent = userRepository.saveOrThrowDuplicate(parent, "이미 사용 중인 아이디예요.");
        consentService.recordSignup(parent, request.consents(), "CLASS_JOIN_SIGNUP");

        tutorStudentService.enrollParentInClass(
                parent, classGroup, request.childName(), request.childBirthYear(), null, request.rosterStudentId());
        return authResponse(parent);
    }

    /** 이미 계정이 있는 학부모가 반 코드로 아이를 한 명 더 올린다. 아이마다 한 번씩 거치면 된다. */
    @Transactional
    public AuthResponse joinExistingParent(CurrentUser caller, JoinExistingClassRequest request) {
        // 같은 학부모의 동시 요청(버튼을 두 번 누르는 등)을 직렬화한다 - 아이 프로필·학생 행이 둘 생기지 않게.
        AppUser parent = userRepository.lockActiveById(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        ClassGroup classGroup = resolveClassGroup(request.classCode());
        tutorStudentService.enrollParentInClass(
                parent, classGroup, request.childName(), request.childBirthYear(), request.childId(),
                request.rosterStudentId());
        return authResponse(parent);
    }

    /** 반 초대 링크 미리보기 - 로그인 없이 반 코드만으로 기관·반·담임 이름을 보여 준다. */
    @Transactional(readOnly = true)
    public ClassPreviewResponse preview(String classCode) {
        return ClassPreviewResponse.of(resolveClassGroup(classCode));
    }

    /** 반 코드로 본 명단 중 아직 학부모가 없는 학생 - 이름이 명단과 다를 때 학부모가 우리 아이를 골라 잇는다. */
    @Transactional(readOnly = true)
    public List<ClassRosterEntryResponse> pendingRoster(String classCode) {
        ClassGroup classGroup = resolveClassGroup(classCode);
        return tutorStudentRepository.findByClassGroup_IdAndLinkedParentUserIsNullAndDeletedAtIsNull(classGroup.getId()).stream()
                .sorted(java.util.Comparator.comparing(TutorStudent::getCreatedAt))
                .map(ClassRosterEntryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClassMembershipResponse> listMemberships(CurrentUser caller) {
        return tutorStudentRepository.findByLinkedParentUser_IdAndDeletedAtIsNullOrderByCreatedAtAsc(caller.userId()).stream()
                .filter(student -> student.getClassGroup() != null)
                .map(ClassMembershipResponse::of)
                .toList();
    }

    /**
     * 학부모가 아이를 반에서 뺀다. 학생 행은 지우지 않고 학부모 연결만 푼다(PENDING_PARENT) - 담임이 다시 초대하거나
     * 명단에서 지울 수 있다. 지난 수업 리포트는 이 학부모에게 계속 보이지만, 반 소속으로 받던 이용권은 끊긴다.
     */
    @Transactional
    public void leaveClass(CurrentUser caller, UUID studentId) {
        TutorStudent student = tutorStudentRepository.findById(studentId)
                .filter(found -> found.getDeletedAt() == null
                        && found.getLinkedParentUser() != null
                        && found.getLinkedParentUser().getId().equals(caller.userId()))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "연결된 학생을 찾을 수 없어요.", 404));
        // 연결돼 있던 동안의 수업 리포트는 연결을 풀어도 이 학부모가 계속 볼 수 있게 남긴다(플레이 이용권은 끊긴다).
        // linkedAt이 없는 예전 연결은 기간을 알 수 없어 그 학생의 기록 전체를 남긴다.
        storyCompletionRepository.keepParentAccess(
                student.getId(), caller.userId(),
                student.getLinkedAt() != null ? student.getLinkedAt() : java.time.Instant.EPOCH);
        student.setLinkedParentUser(null);
        student.setLinkedAt(null);
        student.setChild(null);
        student.setStatus(TutorStudentStatus.PENDING_PARENT);
        tutorStudentRepository.save(student);
    }

    private AuthResponse authResponse(AppUser parent) {
        return new AuthResponse(jwtService.issue(new CurrentUser(parent.getId(), Role.PARENT, null)), userSummaryFactory.of(parent));
    }

    private ClassGroup resolveClassGroup(String classCode) {
        if (classCode == null || classCode.isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "반 코드가 필요해요.");
        }
        ClassGroup classGroup = classGroupRepository.findByJoinCode(classCode.trim().toUpperCase())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_JOIN_CODE, "반 코드를 다시 확인해 주세요.", 404));
        // 탈퇴한 선생님의 개인 반 코드는 더 이상 쓸 수 없다(기관 반은 탈퇴 시 담임 미정으로 되돌아간다).
        if (classGroup.getTutor() != null && classGroup.getTutor().getDeletedAt() != null) {
            throw ApiException.contractError(ErrorCode.INVALID_JOIN_CODE, "더 이상 사용할 수 없는 반 코드예요.", 404);
        }
        return classGroup;
    }

    private AppUser requireOrganizationTutor(UUID organizationId, UUID tutorId) {
        return organizationTutorRepository.findByOrganization_IdAndTutor_Id(organizationId, tutorId)
                .map(link -> link.getTutor())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "이 기관에 소속된 선생님이 아니에요.", 404));
    }

    /** 그 반이 속한 기관의 원장만 - 아니면 403, 반이 없으면 404. 담임 초대(ClassHomeroomInviteService)도 같은 규칙을 쓴다. */
    public ClassGroup requireOwnedByDirector(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = requireClass(classId);
        if (!isOwningDirector(caller, classGroup)) {
            throw forbidden();
        }
        return classGroup;
    }

    /** 반을 볼 수 있는 사람: 그 반이 속한 기관의 원장, 그리고 담임 선생님. */
    private ClassGroup requireVisible(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = requireClass(classId);
        boolean isHomeroomTutor = caller.role() == Role.TUTOR
                && classGroup.getTutor() != null
                && classGroup.getTutor().getId().equals(caller.userId());
        if (!isOwningDirector(caller, classGroup) && !isHomeroomTutor) {
            throw forbidden();
        }
        return classGroup;
    }

    private ClassGroup requireClass(UUID classId) {
        return classGroupRepository.findById(classId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "반을 찾을 수 없어요.", 404));
    }

    private static boolean isOwningDirector(CurrentUser caller, ClassGroup classGroup) {
        return caller.role() == Role.DIRECTOR
                && classGroup.getOrganization() != null
                && classGroup.getOrganization().getId().equals(caller.orgId());
    }

    private static ApiException forbidden() {
        return ApiException.contractError(ErrorCode.FORBIDDEN, "이 반에 접근할 권한이 없어요.", 403);
    }

    private String generateUniqueJoinCode() {
        return joinCodeGenerator.generateUnique(classGroupRepository::existsByJoinCode,
                () -> ApiException.contractError(ErrorCode.INTERNAL_ERROR, "반 코드를 생성하지 못했어요. 다시 시도해 주세요.", 500));
    }
}
