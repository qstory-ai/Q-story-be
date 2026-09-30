package com.qstory.backend.tutor.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.ChildAge;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.common.util.TokenValidation;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.identity.util.AuthValidator;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.tutor.TutorLessonType;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.Weekday;
import com.qstory.backend.tutor.dto.AcceptTutorInviteRequest;
import com.qstory.backend.tutor.dto.BulkCreateTutorStudentsRequest;
import com.qstory.backend.tutor.dto.BulkTutorStudentResult;
import com.qstory.backend.tutor.dto.CreateTutorInviteRequest;
import com.qstory.backend.tutor.dto.CreateTutorScheduleRequest;
import com.qstory.backend.tutor.dto.CreateTutorStudentRequest;
import com.qstory.backend.tutor.dto.TutorInvitePreviewResponse;
import com.qstory.backend.tutor.dto.TutorInviteResponse;
import com.qstory.backend.tutor.dto.TutorScheduleResponse;
import com.qstory.backend.tutor.dto.TutorStudentResponse;
import com.qstory.backend.tutor.dto.UpdateTutorStudentRequest;
import com.qstory.backend.tutor.entity.TutorInvite;
import com.qstory.backend.tutor.entity.TutorSchedule;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorInviteRepository;
import com.qstory.backend.tutor.repository.TutorScheduleRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선생님의 학생 등록/일정/부모 초대. 초대는 랜덤 토큰(SHA-256 해시만 저장) + short code, 14일 TTL, 1회용이다.
 * 초대 수락자는 새로 가입하는 경우도, 이미 로그인된 기존 PARENT 계정인 경우도 있다 - acceptInvite()가 둘 다 받는다.
 */
@Service
public class TutorStudentService {

    private static final Duration INVITE_TTL = Duration.ofDays(14);

    /** fe entities/child/model/avatars.ts CHILD_AVATARS[0].key와 동일. */
    private static final String DEFAULT_CHILD_AVATAR_KEY = "fox";

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final TutorStudentRepository tutorStudentRepository;
    private final TutorScheduleRepository tutorScheduleRepository;
    private final TutorInviteRepository tutorInviteRepository;
    private final AppUserRepository userRepository;
    private final AuthValidator authValidator;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecureTokenGenerator tokenGenerator;
    private final JoinCodeGenerator joinCodeGenerator;
    private final NotificationPublisher notificationPublisher;
    private final ChildRepository childRepository;
    private final TutorClassService tutorClassService;
    private final LessonRepository lessonRepository;
    private final UserSummaryFactory userSummaryFactory;

    public TutorStudentService(
            TutorStudentRepository tutorStudentRepository, TutorScheduleRepository tutorScheduleRepository,
            TutorInviteRepository tutorInviteRepository, AppUserRepository userRepository,
            AuthValidator authValidator, PasswordEncoder passwordEncoder, JwtService jwtService,
            SecureTokenGenerator tokenGenerator, JoinCodeGenerator joinCodeGenerator,
            NotificationPublisher notificationPublisher, ChildRepository childRepository,
            TutorClassService tutorClassService, LessonRepository lessonRepository,
            UserSummaryFactory userSummaryFactory) {
        this.tutorStudentRepository = tutorStudentRepository;
        this.tutorScheduleRepository = tutorScheduleRepository;
        this.tutorInviteRepository = tutorInviteRepository;
        this.userRepository = userRepository;
        this.authValidator = authValidator;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenGenerator = tokenGenerator;
        this.joinCodeGenerator = joinCodeGenerator;
        this.notificationPublisher = notificationPublisher;
        this.childRepository = childRepository;
        this.tutorClassService = tutorClassService;
        this.lessonRepository = lessonRepository;
        this.userSummaryFactory = userSummaryFactory;
    }

    @Transactional
    public TutorStudentResponse createStudent(CurrentUser caller, CreateTutorStudentRequest request) {
        if (isBlank(request.name())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "아이 이름 또는 별명을 입력해 주세요.");
        }
        // 출생연도가 오면 "N세"는 계산한다. 예전 클라이언트가 연령대 라벨만 보내면 그대로 받는다.
        Integer birthYear = ChildAge.validateBirthYear(request.birthYear());
        if (birthYear == null && isBlank(request.ageBand())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "아이의 출생연도를 골라 주세요.");
        }
        TutorLessonType lessonType = TutorLessonType.parseOrDefault(request.lessonType());
        if (lessonType == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "수업 형태는 INDIVIDUAL 또는 CLASS여야 해요.");
        }
        ClassGroup classGroup = null;
        if (lessonType == TutorLessonType.CLASS) {
            if (request.classGroupId() == null) {
                throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "반 수업이면 반을 선택해 주세요.");
            }
            classGroup = tutorClassService.requireVisible(caller, request.classGroupId());
        }
        AppUser tutor = userRepository.getReferenceById(caller.userId());
        TutorStudent student = tutorStudentRepository.save(TutorStudent.builder()
                .tutor(tutor)
                .name(request.name().trim())
                .ageBand(birthYear != null ? ChildAge.tutorLabel(birthYear) : request.ageBand().trim())
                .birthYear(birthYear)
                .classType(request.classType())
                .prepNote(request.prepNote())
                .lessonType(lessonType)
                .classGroup(classGroup)
                .createdAt(Instant.now())
                .build());
        if (classGroup != null) addToScheduledClassLessons(caller.userId(), student, classGroup);
        return TutorStudentResponse.of(student);
    }

    /**
     * 부모가 반 코드로 아이를 그 반의 학생 명단에 올리는 경로. 부모 계정에는 반 소속을 남기지 않고 학생 한 명
     * (CONFIRMED)으로 등록하므로, 이후 수업·리포트·알림은 초대 수락 경로와 똑같이 동작한다. 기관 반에 담임이 아직
     * 없으면 학생은 담임 없이 명단에만 올라가고, 원장이 담임을 배정하면 그 선생님의 학생이 된다(ClassService).
     * 같은 부모가 아이 여러 명을 올리려면 아이마다 이 경로를 한 번씩 거치면 된다.
     */
    @Transactional
    public TutorStudent enrollParentInClass(AppUser parent, ClassGroup classGroup, String childName, Integer birthYearInput) {
        if (isBlank(childName)) {
            throw ApiException.contractError(ErrorCode.CHILD_INFO_REQUIRED, "아이 이름을 입력해 주세요.");
        }
        Integer birthYear = ChildAge.validateBirthYear(birthYearInput);
        if (birthYear == null) {
            throw ApiException.contractError(ErrorCode.CHILD_INFO_REQUIRED, "아이의 출생연도를 골라 주세요.");
        }
        AppUser tutor = classGroup.getTutor();
        TutorStudent student = TutorStudent.builder()
                .tutor(tutor)
                .name(childName.trim())
                .ageBand(ChildAge.tutorLabel(birthYear))
                .birthYear(birthYear)
                .lessonType(TutorLessonType.CLASS)
                .classGroup(classGroup)
                .status(TutorStudentStatus.CONFIRMED)
                .linkedParentUser(parent)
                .createdAt(Instant.now())
                .build();
        student.setChild(resolveChild(parent, student, null));
        // 담임이 없는 반은 (tutor_id, child_id) 유니크 인덱스가 걸리지 않아 같은 아이가 중복으로 올라올 수 있다.
        if (tutorStudentRepository.existsByClassGroup_IdAndChild_IdAndDeletedAtIsNull(classGroup.getId(), student.getChild().getId())) {
            throw ApiException.contractError(ErrorCode.DUPLICATE_CHILD_LINK, "이 아이는 이미 이 반에 등록되어 있어요.", 409);
        }
        try {
            student = tutorStudentRepository.saveAndFlush(student);
        } catch (DataIntegrityViolationException duplicate) {
            throw ApiException.contractError(
                    ErrorCode.DUPLICATE_CHILD_LINK, "이 아이는 이미 이 선생님의 학생으로 등록되어 있어요.", 409);
        }
        if (tutor != null) {
            addToScheduledClassLessons(tutor.getId(), student, classGroup);
        }
        notifyClassEnrollment(parent, student, classGroup);
        return student;
    }

    /** 담임에게, 담임이 아직 없으면 기관 원장에게 알린다. */
    private void notifyClassEnrollment(AppUser parent, TutorStudent student, ClassGroup classGroup) {
        String body = parent.getDisplayName() + "님이 " + classGroup.getName() + " 반에 " + student.getName() + "을(를) 등록했어요.";
        if (classGroup.getTutor() != null) {
            notificationPublisher.publish(
                    classGroup.getTutor().getId(), "tutor-class-parent-joined",
                    student.getName() + " 부모님이 반 코드로 들어왔어요", body,
                    "/tutor/students/" + student.getId(), "tutor-class-parent-joined:" + student.getId());
            return;
        }
        if (classGroup.getOrganization() == null) {
            return;
        }
        userRepository.findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(classGroup.getOrganization().getId(), Role.DIRECTOR)
                .ifPresent(director -> notificationPublisher.publish(
                        director.getId(), "class-parent-joined",
                        "새 학부모가 반에 합류했어요", body,
                        "/organization/classes/" + classGroup.getId(), "class-parent-joined:" + student.getId()));
    }

    /** 한 번에 등록할 수 있는 최대 인원 - 반 하나 규모를 넘는 요청은 실수로 보고 거절한다. */
    static final int BULK_STUDENT_LIMIT = 50;

    /**
     * 반 학생을 한 번에 등록하고 학생마다 초대를 발급한다. 한 명이라도 검증에 실패하면 전체가
     * 롤백된다 - 절반만 등록된 채 응답이 실패하면 선생님이 어디까지 됐는지 알 수 없다.
     */
    @Transactional
    public List<BulkTutorStudentResult> createStudentsBulk(CurrentUser caller, BulkCreateTutorStudentsRequest request) {
        List<BulkCreateTutorStudentsRequest.Student> items = request == null || request.students() == null
                ? List.of() : request.students();
        if (items.isEmpty()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "등록할 학생을 한 명 이상 입력해 주세요.");
        }
        if (items.size() > BULK_STUDENT_LIMIT) {
            throw ApiException.contractError(
                    ErrorCode.VALIDATION_FAILED, "한 번에 " + BULK_STUDENT_LIMIT + "명까지 등록할 수 있어요.");
        }
        List<BulkTutorStudentResult> results = new ArrayList<>();
        for (BulkCreateTutorStudentsRequest.Student item : items) {
            Integer birthYear = item.birthYear() != null ? item.birthYear() : request.defaultBirthYear();
            TutorStudentResponse student = createStudent(caller, new CreateTutorStudentRequest(
                    item.name(), null, request.classType(), request.prepNote(), request.lessonType(),
                    request.classGroupId(), birthYear));
            TutorInviteResponse invite = createInvite(caller, student.id(), new CreateTutorInviteRequest("LINK", null));
            results.add(new BulkTutorStudentResult(student, invite));
        }
        return results;
    }

    @Transactional(readOnly = true)
    public TutorStudentResponse getStudent(CurrentUser caller, UUID studentId) {
        return TutorStudentResponse.of(requireOwnedStudent(caller, studentId));
    }

    @Transactional
    public TutorStudentResponse updateStudent(CurrentUser caller, UUID studentId, UpdateTutorStudentRequest request) {
        TutorStudent student = requireOwnedStudent(caller, studentId);
        // 각 필드가 null이면 그대로 두고, 값이 있으면 반영. 빈 문자열은 명시적 "지우기".
        if (request.classType() != null) {
            String trimmed = request.classType().trim();
            student.setClassType(trimmed.isEmpty() ? null : trimmed);
        }
        if (request.prepNote() != null) {
            String trimmed = request.prepNote().trim();
            student.setPrepNote(trimmed.isEmpty() ? null : trimmed);
        }
        if (request.birthYear() != null) {
            Integer birthYear = ChildAge.validateBirthYear(request.birthYear());
            student.setBirthYear(birthYear);
            student.setAgeBand(ChildAge.tutorLabel(birthYear));
        }
        // 수업 형태/반: classGroupId만 와도 CLASS로 간주. INDIVIDUAL로 바꾸면 반 연결을 지운다.
        ClassGroup previousClass = student.getClassGroup();
        if (request.lessonType() != null || request.classGroupId() != null) {
            TutorLessonType lessonType = request.lessonType() == null
                    ? TutorLessonType.CLASS : TutorLessonType.parseOrDefault(request.lessonType());
            if (lessonType == null) {
                throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "수업 형태는 INDIVIDUAL 또는 CLASS여야 해요.");
            }
            if (lessonType == TutorLessonType.INDIVIDUAL) {
                student.setClassGroup(null);
            } else {
                UUID classGroupId = request.classGroupId() != null
                        ? request.classGroupId()
                        : student.getClassGroup() == null ? null : student.getClassGroup().getId();
                if (classGroupId == null) {
                    throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "반 수업이면 반을 선택해 주세요.");
                }
                student.setClassGroup(tutorClassService.requireVisible(caller, classGroupId));
            }
            student.setLessonType(lessonType);
        }
        // 반이 바뀌면 예정된 반 수업의 참여자도 따라간다 - 옛 반 수업에 남으면 그 수업의 완주 기록·알림이 잘못 간다.
        ClassGroup nextClass = student.getClassGroup();
        UUID previousId = previousClass == null ? null : previousClass.getId();
        UUID nextId = nextClass == null ? null : nextClass.getId();
        if (!Objects.equals(previousId, nextId)) {
            if (previousClass != null) removeFromScheduledClassLessons(caller, student, previousClass);
            if (nextClass != null) addToScheduledClassLessons(caller.userId(), student, nextClass);
        }
        return TutorStudentResponse.of(tutorStudentRepository.save(student));
    }

    @Transactional(readOnly = true)
    public List<TutorStudentResponse> listStudents(CurrentUser caller) {
        return tutorStudentRepository.findByTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(caller.userId()).stream()
                .map(TutorStudentResponse::of)
                .toList();
    }

    /**
     * 소프트 삭제(053) - 행을 남겨야 story_completion이 이 학생을 계속 가리켜 부모가 선생님 리포트를 잃지 않는다.
     * deletedAt을 채우고, 예정 수업 참여자에서 빼고, 열려 있던 초대는 닫는다. 이미 끝난 수업·완주 기록은 그대로다.
     */
    @Transactional
    public void deleteStudent(CurrentUser caller, UUID studentId) {
        TutorStudent student = requireOwnedStudent(caller, studentId);
        Instant now = Instant.now();
        student.setDeletedAt(now);
        for (Lesson lesson : lessonRepository.findByTutor_IdAndStatusAndStudents_Id(
                caller.userId(), LessonStatus.SCHEDULED, student.getId())) {
            lesson.getStudents().removeIf(participant -> participant.getId().equals(student.getId()));
            lesson.setUpdatedAt(now);
            lessonRepository.save(lesson);
        }
        tutorInviteRepository.closeOpenInvites(student.getId(), now);
        tutorStudentRepository.save(student);
    }

    /** 반에 새로 들어온 학생을 그 반의 아직 예정(SCHEDULED)인 수업에 참여자로 넣는다. */
    private void addToScheduledClassLessons(UUID tutorId, TutorStudent student, ClassGroup classGroup) {
        for (Lesson lesson : lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                tutorId, classGroup.getId(), LessonStatus.SCHEDULED)) {
            if (lesson.getStudents().stream().noneMatch(p -> p.getId().equals(student.getId()))) {
                lesson.getStudents().add(student);
                lesson.setUpdatedAt(Instant.now());
                lessonRepository.save(lesson);
            }
        }
    }

    /** 반에서 나간 학생을 그 반의 예정 수업 참여자에서 뺀다. 진행 중·완료 수업은 건드리지 않는다. */
    private void removeFromScheduledClassLessons(CurrentUser caller, TutorStudent student, ClassGroup classGroup) {
        for (Lesson lesson : lessonRepository.findByTutor_IdAndClassGroup_IdAndStatus(
                caller.userId(), classGroup.getId(), LessonStatus.SCHEDULED)) {
            if (lesson.getStudents().removeIf(p -> p.getId().equals(student.getId()))) {
                lesson.setUpdatedAt(Instant.now());
                lessonRepository.save(lesson);
            }
        }
    }

    /**
     * 이 선생님이 등록한 모든 학생의 일정을 통틀어 - "주간 일정" 화면이 학생별로 다시 조회할 필요
     * 없게. @Transactional(readOnly=true) 필수 - TutorScheduleResponse.of()가 지연 로딩된
     * tutorStudent.getName()을 읽는데, 세션이 이미 닫힌 뒤(트랜잭션 밖)라면
     * LazyInitializationException이 난다(id만 읽으면 프록시가 안 깨어나 괜찮지만, name처럼
     * 실제 컬럼을 읽으려면 DB를 다시 쳐야 해서 열린 세션이 필요하다).
     */
    @Transactional(readOnly = true)
    public List<TutorScheduleResponse> listSchedules(CurrentUser caller) {
        return tutorScheduleRepository.findByTutorStudent_Tutor_IdAndTutorStudent_DeletedAtIsNullOrderByCreatedAtAsc(caller.userId()).stream()
                .map(TutorScheduleResponse::of)
                .toList();
    }

    @Transactional
    public TutorScheduleResponse createSchedule(CurrentUser caller, UUID studentId, CreateTutorScheduleRequest request) {
        TutorStudent student = requireOwnedStudent(caller, studentId);
        Weekday weekday = parseWeekday(request.weekday());
        LocalTime startTime = parseTime(request.startTime(), "시작 시간");
        LocalTime endTime = parseTime(request.endTime(), "종료 시간");
        if (!startTime.isBefore(endTime)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "종료 시간은 시작 시간보다 늦어야 해요.");
        }
        LocalDate startDate = parseDate(request.startDate());
        if (isBlank(request.location())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "수업 장소를 입력해 주세요.");
        }
        TutorSchedule schedule = tutorScheduleRepository.save(TutorSchedule.builder()
                .tutorStudent(student)
                .weekday(weekday)
                .startTime(startTime)
                .endTime(endTime)
                .startDate(startDate)
                .location(request.location().trim())
                .reminderEnabled(request.reminderEnabled() == null || request.reminderEnabled())
                .createdAt(Instant.now())
                .build());
        return TutorScheduleResponse.of(schedule);
    }

    @Transactional
    public TutorInviteResponse createInvite(CurrentUser caller, UUID studentId, CreateTutorInviteRequest request) {
        TutorStudent student = requireOwnedStudent(caller, studentId);
        if (!"SMS".equals(request.method()) && !"LINK".equals(request.method())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "초대 방법은 SMS 또는 LINK여야 해요.");
        }
        if ("SMS".equals(request.method()) && isBlank(request.phoneNumber())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "휴대폰 번호를 입력해 주세요.");
        }
        String rawToken = tokenGenerator.generate();
        String shortCode = joinCodeGenerator.generateUnique(tutorInviteRepository::existsByShortCode,
                () -> ApiException.contractError(ErrorCode.INTERNAL_ERROR, "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해 주세요."));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(INVITE_TTL);
        tutorInviteRepository.save(TutorInvite.builder()
                .tutorStudent(student)
                .tokenHash(DigestUtil.sha256Hex(rawToken))
                .shortCode(shortCode)
                .method(request.method())
                .phoneNumber(request.phoneNumber())
                .expiresAt(expiresAt)
                .createdAt(now)
                .build());
        return new TutorInviteResponse(rawToken, shortCode, expiresAt);
    }

    /**
     * 소비하지 않고 미리보기만 - 만료/사용된 토큰이면 초대 수락과 동일한 에러를 던진다.
     * @Transactional(readOnly=true) 필수 - invite.getTutorStudent()와 student.getTutor()가
     * 둘 다 지연 로딩이라, 세션이 열려 있어야 name/displayName을 읽을 수 있다.
     */
    @Transactional(readOnly = true)
    public TutorInvitePreviewResponse previewInvite(String rawToken) {
        return previewOf(requireInviteByToken(rawToken));
    }

    /** short_code 기반 미리보기 - previewInvite와 응답 형태는 같고 조회 경로만 다르다. */
    @Transactional(readOnly = true)
    public TutorInvitePreviewResponse previewInviteByCode(String shortCode) {
        return previewOf(requireInviteByShortCode(shortCode));
    }

    private static TutorInvitePreviewResponse previewOf(TutorInvite invite) {
        TutorStudent student = invite.getTutorStudent();
        return new TutorInvitePreviewResponse(
                student.getName(), student.currentAgeBand(), student.getTutor().getDisplayName(), student.getBirthYear());
    }

    /**
     * callerOrNull이 있으면(로그인된 PARENT) 그 계정에 바로 연결한다. 없으면 request의 email/
     * password/displayName으로 새 PARENT 계정을 만들며 연결한다 - ClassService.join()과 동일한
     * "초대 수락이 곧 회원가입"인 경우다.
     */
    @Transactional
    public AuthResponse acceptInvite(Optional<CurrentUser> callerOrNull, String rawToken, AcceptTutorInviteRequest request) {
        return consumeInvite(callerOrNull, requireInviteByToken(rawToken), request);
    }

    /** short_code 기반 수락 - acceptInvite와 후속 처리는 동일. 조회 경로만 다르다. */
    @Transactional
    public AuthResponse acceptInviteByCode(
            Optional<CurrentUser> callerOrNull, String shortCode, AcceptTutorInviteRequest request) {
        return consumeInvite(callerOrNull, requireInviteByShortCode(shortCode), request);
    }

    private AuthResponse consumeInvite(
            Optional<CurrentUser> callerOrNull, TutorInvite invite, AcceptTutorInviteRequest request) {
        // 학생 행을 먼저 잠근다(select ... for update). 같은 학생의 초대를 두 보호자가 동시에 수락하면
        // 둘 다 "아직 연결 안 됨" 검사를 통과해 아이 프로필이 두 개 생기고 마지막 쓰기가 이기던 경합을
        // 여기서 직렬화한다 - 두 번째 트랜잭션은 잠금이 풀린 뒤 이미 CONFIRMED가 된 행을 본다.
        TutorStudent student = tutorStudentRepository.lockById(invite.getTutorStudent().getId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_INVITE, "초대에 연결된 학생을 찾을 수 없어요.", 410));
        if (student.getDeletedAt() != null) {
            throw ApiException.contractError(
                    ErrorCode.INVALID_INVITE, "선생님이 이 학생 등록을 삭제해서 초대를 더 이상 사용할 수 없어요.", 410);
        }
        AppUser parent = callerOrNull.isPresent() ? existingParent(callerOrNull.get()) : newParent(request);
        // 이미 다른 보호자가 연결된 학생을 두 번째 초대로 조용히 덮어쓰지 않는다. 탈퇴한 보호자는
        // AuthService.deleteAccount가 연결을 풀고 PENDING으로 되돌리므로 여기서 영구히 막히지 않는다.
        AppUser alreadyLinked = student.getLinkedParentUser();
        if (student.getStatus() == TutorStudentStatus.CONFIRMED
                && alreadyLinked != null && !alreadyLinked.getId().equals(parent.getId())) {
            throw ApiException.contractError(
                    ErrorCode.INVALID_INVITE, "이 학생은 이미 다른 보호자 계정과 연결되어 있어요.", 409);
        }
        Instant now = Instant.now();
        // 조건부 소진 - usedAt이 null일 때만 1행이 바뀐다. 0이면 잠금을 기다리는 사이 다른 수락이 이 초대를
        // 먼저 썼거나(같은 초대 동시 수락) 같은 학생의 다른 초대가 수락되며 닫힌 것이다.
        if (tutorInviteRepository.markUsed(invite.getId(), now) == 0) {
            throw ApiException.contractError(
                    ErrorCode.INVALID_INVITE, "이미 사용된 초대예요. 다른 보호자가 먼저 수락했을 수 있어요.", 410);
        }
        tutorInviteRepository.closeOpenInvites(student.getId(), now);
        student.setLinkedParentUser(parent);
        student.setStatus(TutorStudentStatus.CONFIRMED);
        // 부모 쪽 아이 프로필까지 연결해야 "수락"이 완결된다 - 이 행이 없으면 부모 홈의 아이 목록에
        // 아무것도 없고, 선생님 세션의 완주 기록도 아이에게 이어지지 않는다.
        student.setChild(resolveChild(parent, student, request == null ? null : request.childId()));
        try {
            tutorStudentRepository.saveAndFlush(student);
        } catch (DataIntegrityViolationException duplicate) {
            // 053의 (tutor_id, child_id) 유니크 인덱스 - 같은 선생님의 다른 학생 등록이 이미 이 아이를 가리킨다.
            throw ApiException.contractError(
                    ErrorCode.DUPLICATE_CHILD_LINK, "이 아이는 이미 같은 선생님의 다른 학생 등록에 연결되어 있어요. 선생님께 확인해 주세요.", 409);
        }
        // 학생을 등록한 튜터에게 "부모 연결이 완료됐다" 알림. href는 학생 상세로 - 튜터가 곧바로
        // 수업 준비를 시작할 수 있게. dedupKey는 invite.id로 안정화해 중복 발행 방지.
        AppUser tutor = student.getTutor();
        if (tutor != null) {
            notificationPublisher.publish(
                    tutor.getId(),
                    "tutor-invite-accepted",
                    student.getName() + " 부모님이 연결을 수락했어요",
                    parent.getDisplayName() + "님과 " + student.getName() + " 수업을 이어갈 수 있어요.",
                    "/tutor/students/" + student.getId(),
                    "tutor-invite-accepted:" + invite.getId());
        }
        CurrentUser currentUser = new CurrentUser(parent.getId(), Role.PARENT, null);
        return new AuthResponse(jwtService.issue(currentUser), userSummaryFactory.of(parent));
    }

    /**
     * 초대 수락 시 학생에 붙일 아이 프로필. (1) 부모가 childId를 지정했으면 본인 소유인지 확인해 그 아이,
     * (2) 아니면 같은 이름(공백·대소문자 무시)의 기존 아이 - 여럿이면 출생연도로 좁히고 그래도 여럿이면
     * 409 CHILD_SELECTION_REQUIRED로 부모가 childId를 고르게 한다, (3) 없으면 초대에 실린 이름·연령대로
     * 새 아이를 만든다. 새로 만드는 경우 아바타는 프론트 프리셋 첫 번째('fox')와 같은 기본값이라
     * 부모가 프로필에서 바꿀 수 있다. 이미 이 학생에 아이가 붙어 있으면(같은 부모의 재수락) 그대로 둔다.
     */
    private Child resolveChild(AppUser parent, TutorStudent student, UUID requestedChildId) {
        if (requestedChildId != null) {
            return childRepository.findByIdAndParent_Id(requestedChildId, parent.getId())
                    .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "아이 프로필을 찾을 수 없어요.", 404));
        }
        if (student.getChild() != null && student.getChild().getParent().getId().equals(parent.getId())) {
            return student.getChild();
        }
        // 이름 매칭 - 선생님이 적은 별명이라 형제나 같은 별명의 아이와 겹칠 수 있다. 후보가 여럿이면
        // 출생연도로 좁히고, 그래도 여럿이면 조용히 첫 아이를 고르지 않고 부모가 childId로 고르게 한다.
        String wanted = normalizeName(student.getName());
        List<Child> matches = new ArrayList<>();
        for (Child existing : childRepository.findByParent_IdOrderByCreatedAtAsc(parent.getId())) {
            if (normalizeName(existing.getName()).equals(wanted)) matches.add(existing);
        }
        if (matches.size() > 1 && student.getBirthYear() != null) {
            List<Child> sameYear = matches.stream()
                    .filter(c -> student.getBirthYear().equals(c.getBirthYear()))
                    .toList();
            if (!sameYear.isEmpty()) matches = sameYear;
        }
        if (matches.size() > 1) {
            throw ApiException.contractError(
                    ErrorCode.CHILD_SELECTION_REQUIRED,
                    "같은 이름의 아이가 여러 명이에요. 연결할 아이 프로필을 골라 다시 수락해 주세요.", 409);
        }
        if (matches.size() == 1) return matches.get(0);
        Instant now = Instant.now();
        return childRepository.save(Child.builder()
                .parent(parent)
                .name(student.getName())
                .ageBand(student.getBirthYear() != null
                        ? ChildAge.parentBand(student.getBirthYear())
                        : childAgeBandFor(student.getAgeBand()))
                .birthYear(student.getBirthYear())
                .avatarKey(DEFAULT_CHILD_AVATAR_KEY)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 선생님 쪽 학생 연령대는 "7세"처럼 한 살 단위, 부모 쪽 아이 프로필은 "6-7" 같은 구간이다
     * (fe entities/child/model/age-band.ts의 ageBandFromLabel과 같은 규칙). 숫자가 없으면 "6-7".
     */
    static String childAgeBandFor(String tutorAgeBand) {
        Matcher matcher = DIGITS.matcher(tutorAgeBand == null ? "" : tutorAgeBand);
        if (!matcher.find()) return "6-7";
        int age = Integer.parseInt(matcher.group());
        if (age <= 5) return "4-5";
        if (age <= 7) return "6-7";
        if (age <= 9) return "8-9";
        if (age <= 11) return "10-11";
        return "12+";
    }

    private TutorInvite requireInviteByToken(String rawToken) {
        TutorInvite invite = tutorInviteRepository.findByTokenHash(DigestUtil.sha256Hex(rawToken))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_INVITE, "초대 링크가 올바르지 않아요.", 410));
        TokenValidation.requireUsable(invite.getUsedAt(), invite.getExpiresAt(),
                ErrorCode.INVALID_INVITE, "만료되었거나 이미 사용된 초대 링크예요.", 410);
        return invite;
    }

    private TutorInvite requireInviteByShortCode(String shortCode) {
        String normalized = shortCode == null ? "" : shortCode.trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw ApiException.contractError(ErrorCode.INVALID_INVITE, "초대 코드가 올바르지 않아요.", 410);
        }
        TutorInvite invite = tutorInviteRepository.findByShortCode(normalized)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_INVITE, "초대 코드가 올바르지 않아요.", 410));
        TokenValidation.requireUsable(invite.getUsedAt(), invite.getExpiresAt(),
                ErrorCode.INVALID_INVITE, "만료되었거나 이미 사용된 초대 코드예요.", 410);
        return invite;
    }

    private AppUser existingParent(CurrentUser caller) {
        if (caller.role() != Role.PARENT) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "학부모 계정만 연결을 수락할 수 있어요.", 403);
        }
        return userRepository.findById(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
    }

    private AppUser newParent(AcceptTutorInviteRequest request) {
        authValidator.validateSignup(
                new SignupOrganizationOwnerRequest(request.loginId(), request.email(), request.password(), request.displayName()));
        AppUser parent = AppUser.builder()
                .role(Role.PARENT)
                .loginId(request.loginId().trim().toLowerCase())
                .email(request.email().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName().trim())
                .createdAt(Instant.now())
                .build();
        return userRepository.saveOrThrowDuplicate(parent, "이미 사용 중인 아이디예요.");
    }

    private TutorStudent requireOwnedStudent(CurrentUser caller, UUID studentId) {
        return tutorStudentRepository.findByIdAndTutor_IdAndDeletedAtIsNull(studentId, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "학생을 찾을 수 없어요.", 404));
    }

    private static Weekday parseWeekday(String value) {
        try {
            return Weekday.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "수업 요일을 다시 확인해 주세요.");
        }
    }

    private static LocalTime parseTime(String value, String label) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, label + "을 다시 확인해 주세요.");
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "시작일을 다시 확인해 주세요.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
