package com.qstory.backend.tutor.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.common.util.TokenValidation;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.dto.UserSummary;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.util.AuthValidator;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.Weekday;
import com.qstory.backend.tutor.dto.AcceptTutorInviteRequest;
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
import com.qstory.backend.tutor.repository.TutorInviteRepository;
import com.qstory.backend.tutor.repository.TutorScheduleRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문 선생님의 학생 등록/일정/부모 초대 - org.service.ClassService의 초대 메커니즘(랜덤 토큰 생성,
 * SHA-256 해시 저장, 14일 TTL, 1회용 소진)을 그대로 재사용한다. ClassService.join()과 다른 점 하나:
 * 초대 수락자는 새로 가입하는 경우도, 이미 로그인된 기존 PARENT 계정인 경우도 있을 수 있다(자녀가
 * 이미 다른 경로로 부모 계정을 갖고 있는 흔한 케이스) - acceptInvite()가 둘 다 받는다.
 */
@Service
public class TutorStudentService {

    private static final Duration INVITE_TTL = Duration.ofDays(14);

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

    public TutorStudentService(
            TutorStudentRepository tutorStudentRepository, TutorScheduleRepository tutorScheduleRepository,
            TutorInviteRepository tutorInviteRepository, AppUserRepository userRepository,
            AuthValidator authValidator, PasswordEncoder passwordEncoder, JwtService jwtService,
            SecureTokenGenerator tokenGenerator, JoinCodeGenerator joinCodeGenerator,
            NotificationPublisher notificationPublisher) {
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
    }

    @Transactional
    public TutorStudentResponse createStudent(CurrentUser caller, CreateTutorStudentRequest request) {
        if (isBlank(request.name())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "아이 이름 또는 별명을 입력해 주세요.");
        }
        if (isBlank(request.ageBand())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "연령대를 선택해 주세요.");
        }
        AppUser tutor = userRepository.getReferenceById(caller.userId());
        TutorStudent student = tutorStudentRepository.save(TutorStudent.builder()
                .tutor(tutor)
                .name(request.name().trim())
                .ageBand(request.ageBand().trim())
                .classType(request.classType())
                .prepNote(request.prepNote())
                .createdAt(Instant.now())
                .build());
        return TutorStudentResponse.of(student);
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
        return TutorStudentResponse.of(tutorStudentRepository.save(student));
    }

    public List<TutorStudentResponse> listStudents(CurrentUser caller) {
        return tutorStudentRepository.findByTutor_IdOrderByCreatedAtAsc(caller.userId()).stream()
                .map(TutorStudentResponse::of)
                .toList();
    }

    /**
     * 학생 hard delete. 스키마의 cascade 규칙 상 tutor_invite / tutor_schedule /
     * tutor_lesson_plan / lesson_student 4개는 함께 삭제되고, story_completion.
     * tutor_student_id는 set null로 남아 리포트 히스토리는 보존된다(단, "누구와 진행했는지"
     * 라벨은 잃는다). 선생님이 명시적으로 지운 학생은 목록/일정에서 완전히 사라져야 UX가
     * 정직하므로 soft delete 대신 hard delete를 선택.
     */
    @Transactional
    public void deleteStudent(CurrentUser caller, UUID studentId) {
        TutorStudent student = requireOwnedStudent(caller, studentId);
        tutorStudentRepository.delete(student);
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
        return tutorScheduleRepository.findByTutorStudent_Tutor_IdOrderByCreatedAtAsc(caller.userId()).stream()
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
        String shortCode = generateUniqueShortCode();
        Instant expiresAt = Instant.now().plus(INVITE_TTL);
        tutorInviteRepository.save(TutorInvite.builder()
                .tutorStudent(student)
                .tokenHash(DigestUtil.sha256Hex(rawToken))
                .shortCode(shortCode)
                .method(request.method())
                .phoneNumber(request.phoneNumber())
                .expiresAt(expiresAt)
                .createdAt(Instant.now())
                .build());
        return new TutorInviteResponse(rawToken, shortCode, expiresAt);
    }

    /** ClassGroup.joinCode 생성과 동일한 접근 - 8자 코드가 이미 존재하면 다시 시도한다.
     *  탈출은 이론상 필요 없지만(31^8이 매우 크지만), 방어적으로 최대 10회 시도만. */
    private String generateUniqueShortCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = joinCodeGenerator.generate();
            if (!tutorInviteRepository.existsByShortCode(code)) return code;
        }
        throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
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
        return new TutorInvitePreviewResponse(student.getName(), student.getAgeBand(), student.getTutor().getDisplayName());
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
        AppUser parent = callerOrNull.isPresent() ? existingParent(callerOrNull.get()) : newParent(request);

        invite.setUsedAt(Instant.now());
        tutorInviteRepository.save(invite);

        TutorStudent student = invite.getTutorStudent();
        student.setLinkedParentUser(parent);
        student.setStatus(TutorStudentStatus.CONFIRMED);
        tutorStudentRepository.save(student);

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

        CurrentUser currentUser = new CurrentUser(parent.getId(), Role.PARENT, null, null);
        return new AuthResponse(jwtService.issue(currentUser), UserSummary.of(parent));
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
        return tutorStudentRepository.findByIdAndTutor_Id(studentId, caller.userId())
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
