package com.qstory.backend.org.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.identity.util.AuthValidator;
import com.qstory.backend.org.dto.ClassMembershipResponse;
import com.qstory.backend.org.dto.ClassPreviewResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.ClassStudentResponse;
import com.qstory.backend.org.dto.CreateClassRequest;
import com.qstory.backend.org.dto.JoinClassRequest;
import com.qstory.backend.org.dto.JoinExistingClassRequest;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import com.qstory.backend.tutor.service.TutorStudentService;
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

    public ClassService(
            ClassGroupRepository classGroupRepository, TutorStudentRepository tutorStudentRepository,
            OrganizationTutorRepository organizationTutorRepository, AppUserRepository userRepository,
            OrganizationService organizationService, JoinCodeGenerator joinCodeGenerator,
            AuthValidator authValidator, PasswordEncoder passwordEncoder, JwtService jwtService,
            TutorStudentService tutorStudentService, UserSummaryFactory userSummaryFactory) {
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
     * 담임이 없는 반에 담임을 배정한다. 담임이 정해지기 전에 명단에 올라온 학생이 그 선생님의 학생이 되고,
     * 이미 담임이 있는 반의 교체는 수업·기록이 선생님에게 묶여 있어 지원하지 않는다.
     */
    @Transactional
    public ClassResponse assignHomeroom(CurrentUser caller, UUID classId, UUID tutorId) {
        ClassGroup classGroup = requireOwnedByDirector(caller, classId);
        if (classGroup.getTutor() != null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "이미 담임 선생님이 있는 반이에요.", 409);
        }
        if (tutorId == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "담임으로 배정할 선생님을 골라 주세요.");
        }
        AppUser homeroom = requireOrganizationTutor(classGroup.getOrganization().getId(), tutorId);
        classGroup.setTutor(homeroom);
        classGroupRepository.save(classGroup);
        // 대기 학생을 담임에게 넘긴다. 담임이 이미 같은 아이를 다른 학생 등록으로 갖고 있으면((tutor_id, child_id)
        // 유니크) 그 학생만 대기로 남겨 배정 자체는 막지 않는다 - 명단에는 그대로 있고 이용권도 유지된다.
        List<TutorStudent> movable = tutorStudentRepository.findByClassGroup_IdAndTutorIsNullAndDeletedAtIsNull(classId).stream()
                .filter(student -> student.getChild() == null
                        || !tutorStudentRepository.existsByTutor_IdAndChild_IdAndDeletedAtIsNull(homeroom.getId(), student.getChild().getId()))
                .toList();
        movable.forEach(student -> student.setTutor(homeroom));
        tutorStudentRepository.saveAll(movable);
        return ClassResponse.of(classGroup);
    }

    /** 반 코드로 학부모 계정을 만들고 아이를 그 반의 학생으로 올린다 - 가입과 동시에 연결된다. */
    @Transactional
    public AuthResponse join(JoinClassRequest request) {
        ClassGroup classGroup = resolveClassGroup(request.classCode());
        authValidator.validateSignup(new SignupOrganizationOwnerRequest(
                request.loginId(), request.email(), request.password(), request.displayName()));

        AppUser parent = AppUser.builder()
                .role(Role.PARENT)
                .loginId(request.loginId().trim().toLowerCase())
                .email(request.email().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName().trim())
                .createdAt(Instant.now())
                .build();
        parent = userRepository.saveOrThrowDuplicate(parent, "이미 사용 중인 아이디예요.");

        tutorStudentService.enrollParentInClass(parent, classGroup, request.childName(), request.childBirthYear(), null);
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
                parent, classGroup, request.childName(), request.childBirthYear(), request.childId());
        return authResponse(parent);
    }

    /** 반 초대 링크 미리보기 - 로그인 없이 반 코드만으로 기관·반·담임 이름을 보여 준다. */
    @Transactional(readOnly = true)
    public ClassPreviewResponse preview(String classCode) {
        return ClassPreviewResponse.of(resolveClassGroup(classCode));
    }

    @Transactional(readOnly = true)
    public List<ClassMembershipResponse> listMemberships(CurrentUser caller) {
        return tutorStudentRepository.findByLinkedParentUser_IdAndDeletedAtIsNullOrderByCreatedAtAsc(caller.userId()).stream()
                .filter(student -> student.getClassGroup() != null)
                .map(ClassMembershipResponse::of)
                .toList();
    }

    /**
     * 학부모가 아이를 반에서 뺀다. 학생 행은 지우지 않고 학부모 연결만 푼다(PENDING_PARENT) - 부모 계정 탈퇴와
     * 같은 처리라, 담임이 다시 초대하거나 명단에서 지울 수 있고 지난 수업 기록은 그대로 남는다.
     */
    @Transactional
    public void leaveClass(CurrentUser caller, UUID studentId) {
        TutorStudent student = tutorStudentRepository.findById(studentId)
                .filter(found -> found.getDeletedAt() == null
                        && found.getLinkedParentUser() != null
                        && found.getLinkedParentUser().getId().equals(caller.userId()))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "연결된 학생을 찾을 수 없어요.", 404));
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

    private ClassGroup requireOwnedByDirector(CurrentUser caller, UUID classId) {
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
