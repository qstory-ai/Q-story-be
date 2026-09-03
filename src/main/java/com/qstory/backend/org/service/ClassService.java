package com.qstory.backend.org.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.common.util.TokenValidation;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.UserSummary;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.util.AuthValidator;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.dto.ClassInviteResponse;
import com.qstory.backend.org.dto.ClassMemberResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.CreateClassRequest;
import com.qstory.backend.org.dto.JoinClassRequest;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassInvite;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.repository.ClassInviteRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassService {

    private static final Duration INVITE_TTL = Duration.ofDays(14);

    private final ClassGroupRepository classGroupRepository;
    private final ClassInviteRepository classInviteRepository;
    private final AppUserRepository userRepository;
    private final OrganizationService organizationService;
    private final JoinCodeGenerator joinCodeGenerator;
    private final AuthValidator authValidator;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecureTokenGenerator tokenGenerator;
    private final NotificationPublisher notificationPublisher;

    public ClassService(
            ClassGroupRepository classGroupRepository, ClassInviteRepository classInviteRepository,
            AppUserRepository userRepository, OrganizationService organizationService,
            JoinCodeGenerator joinCodeGenerator, AuthValidator authValidator,
            PasswordEncoder passwordEncoder, JwtService jwtService, SecureTokenGenerator tokenGenerator,
            NotificationPublisher notificationPublisher) {
        this.classGroupRepository = classGroupRepository;
        this.classInviteRepository = classInviteRepository;
        this.userRepository = userRepository;
        this.organizationService = organizationService;
        this.joinCodeGenerator = joinCodeGenerator;
        this.authValidator = authValidator;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenGenerator = tokenGenerator;
        this.notificationPublisher = notificationPublisher;
    }

    @Transactional
    public ClassResponse create(CurrentUser caller, UUID organizationId, CreateClassRequest request) {
        Organization organization = organizationService.requireOwned(caller, organizationId);
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "반 이름을 입력해 주세요.");
        }
        authValidator.validatePassword(request.initialPassword());

        ClassGroup classGroup = classGroupRepository.save(ClassGroup.builder()
                .organization(organization)
                .name(request.name().trim())
                .joinCode(generateUniqueJoinCode())
                .createdAt(Instant.now())
                .build());

        AppUser classAccount = AppUser.builder()
                .role(Role.CLASS_ACCOUNT)
                .loginId(classAccountLoginId(classGroup))
                .passwordHash(passwordEncoder.encode(request.initialPassword()))
                .displayName(request.name().trim())
                .organization(organization)
                .classGroup(classGroup)
                .createdAt(Instant.now())
                .build();
        userRepository.saveOrThrowDuplicate(classAccount, "반 계정 아이디가 이미 사용 중이에요.");
        return ClassResponse.of(classGroup);
    }

    public List<ClassResponse> list(CurrentUser caller, UUID organizationId) {
        organizationService.requireOwned(caller, organizationId);
        return classGroupRepository.findByOrganization_IdOrderByCreatedAtAsc(organizationId).stream()
                .map(ClassResponse::of)
                .toList();
    }

    public ClassResponse get(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = requireVisible(caller, classId);
        return ClassResponse.of(classGroup);
    }

    /**
     * IA "반 상세 > 반에 속한 부모(학생) 목록" - PARENT 역할이면서 이 반에 조인된 사용자만.
     * 반 계정(CLASS_ACCOUNT) 자체는 이 목록에서 제외한다(그건 별도의 존재).
     */
    @Transactional(readOnly = true)
    public List<ClassMemberResponse> listParents(CurrentUser caller, UUID classId) {
        requireVisible(caller, classId);
        return userRepository.findByClassGroup_IdAndRoleAndDeletedAtIsNullOrderByCreatedAtDesc(classId, Role.PARENT).stream()
                .map(ClassMemberResponse::of)
                .toList();
    }

    @Transactional
    public ClassInviteResponse createInvite(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = requireVisible(caller, classId);
        if (caller.role() != Role.DIRECTOR) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "기관 및 단체 계정만 초대를 만들 수 있어요.", 403);
        }
        String rawToken = tokenGenerator.generate();
        Instant expiresAt = Instant.now().plus(INVITE_TTL);
        classInviteRepository.save(ClassInvite.builder()
                .classGroup(classGroup)
                .tokenHash(DigestUtil.sha256Hex(rawToken))
                .expiresAt(expiresAt)
                .createdAt(Instant.now())
                .build());
        return new ClassInviteResponse(rawToken, expiresAt);
    }

    @Transactional
    public AuthResponse join(JoinClassRequest request) {
        ClassGroup classGroup = resolveClassGroup(request);
        authValidator.validateSignup(new com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest(
                request.loginId(), request.email(), request.password(), request.displayName()));

        AppUser parent = AppUser.builder()
                .role(Role.PARENT)
                .loginId(request.loginId().trim().toLowerCase())
                .email(request.email().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName().trim())
                .organization(classGroup.getOrganization())
                .classGroup(classGroup)
                .createdAt(Instant.now())
                .build();
        parent = userRepository.saveOrThrowDuplicate(parent, "이미 사용 중인 아이디예요.");

        // 반이 속한 기관의 원장에게 "새 학부모가 반에 합류했다" 알림. 원장이 여러 반을 운영할 때
        // 어느 반인지 곧바로 알 수 있게 body에 반 이름을 넣고 href는 반 상세로 보낸다. dedupKey는
        // 새로 생성된 parent.id로 안정화 - 같은 부모 계정이 여러 경로로 join()을 성공시킬 수는
        // 없으므로(loginId 중복 방지) 사실상 매 발행이 unique다.
        AppUser savedParent = parent;
        ClassGroup joinedClass = classGroup;
        userRepository
                .findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(joinedClass.getOrganization().getId(), Role.DIRECTOR)
                .ifPresent(director -> notificationPublisher.publish(
                        director.getId(),
                        "class-parent-joined",
                        "새 학부모가 반에 합류했어요",
                        savedParent.getDisplayName() + "님이 " + joinedClass.getName() + " 반에 참여했어요.",
                        "/organization/classes/" + joinedClass.getId(),
                        "class-parent-joined:" + savedParent.getId()));

        CurrentUser currentUser = new CurrentUser(
                parent.getId(), Role.PARENT, classGroup.getOrganization().getId(), classGroup.getId());
        return new AuthResponse(jwtService.issue(currentUser), UserSummary.of(parent));
    }

    private ClassGroup resolveClassGroup(JoinClassRequest request) {
        boolean hasCode = request.classCode() != null && !request.classCode().isBlank();
        boolean hasInvite = request.inviteToken() != null && !request.inviteToken().isBlank();
        if (hasCode == hasInvite) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "반 코드 또는 초대 링크가 필요해요.");
        }
        if (hasInvite) {
            return resolveByInvite(request.inviteToken().trim());
        }
        return classGroupRepository.findByJoinCode(request.classCode().trim().toUpperCase())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_JOIN_CODE, "반 코드를 다시 확인해 주세요.", 404));
    }

    private ClassGroup resolveByInvite(String rawToken) {
        ClassInvite invite = classInviteRepository.findByTokenHash(DigestUtil.sha256Hex(rawToken))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_INVITE, "초대 링크가 올바르지 않아요.", 410));
        TokenValidation.requireUsable(invite.getUsedAt(), invite.getExpiresAt(),
                ErrorCode.INVALID_INVITE, "만료되었거나 이미 사용된 초대 링크예요.", 410);
        invite.setUsedAt(Instant.now());
        classInviteRepository.save(invite);
        return invite.getClassGroup();
    }

    /** DIRECTOR는 자신의 소속 기관에 있는 어떤 반이든 볼 수 있고, CLASS_ACCOUNT는 자기 자신만 볼 수 있다. */
    private ClassGroup requireVisible(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = classGroupRepository.findById(classId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "반을 찾을 수 없어요.", 404));
        boolean isOwningOrganizationOwner = caller.role() == Role.DIRECTOR
                && classGroup.getOrganization().getId().equals(caller.orgId());
        boolean isThisClassAccount = caller.role() == Role.CLASS_ACCOUNT && classId.equals(caller.classId());
        if (!isOwningOrganizationOwner && !isThisClassAccount) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 반에 접근할 권한이 없어요.", 403);
        }
        return classGroup;
    }

    private String classAccountLoginId(ClassGroup classGroup) {
        return "class." + classGroup.getId();
    }

    private String generateUniqueJoinCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = joinCodeGenerator.generate();
            if (!classGroupRepository.existsByJoinCode(code)) {
                return code;
            }
        }
        throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "반 코드를 생성하지 못했어요. 다시 시도해 주세요.", 500);
    }
}
