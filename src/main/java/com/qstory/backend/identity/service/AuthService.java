package com.qstory.backend.identity.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.identity.OAuthProvider;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.ChangePasswordRequest;
import com.qstory.backend.identity.dto.ConfirmPasswordResetRequest;
import com.qstory.backend.identity.dto.DeleteAccountRequest;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.OAuthLoginRequest;
import com.qstory.backend.identity.dto.RequestPasswordResetRequest;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.dto.UpdateProfileRequest;
import com.qstory.backend.identity.dto.UserSummary;
import com.qstory.backend.identity.entity.AccountDeletionFeedback;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.entity.PasswordResetToken;
import com.qstory.backend.identity.repository.AccountDeletionFeedbackRepository;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.repository.PasswordResetTokenRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.oauth.GoogleOAuthVerifier;
import com.qstory.backend.identity.service.oauth.KakaoOAuthVerifier;
import com.qstory.backend.identity.util.AuthValidator;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 의도적으로 짧게 유지한다 - 학급 초대(class invite)와 달리, 이 토큰은 계정의 비밀번호를 다시 쓸 수 있다. */
    private static final Duration PASSWORD_RESET_TTL = Duration.ofMinutes(30);

    /** 탈퇴 사유 - 자유 텍스트 대신 고정 목록으로 받아 통계를 낼 수 있게 한다. 문구는 프론트 MyPageDeleteAccountPage와 맞춰야 한다. */
    private static final java.util.Set<String> DELETE_REASON_CATEGORIES = java.util.Set.of(
            "이용료가 부담돼요", "아이가 흥미를 느끼지 못해요", "원하는 콘텐츠가 없어요", "다른 서비스를 이용해요", "기타");

    private static final int MAX_REASON_DETAIL_LENGTH = 2000;

    /** 소셜 로그인으로 새로 만들 수 있는 역할 - Role.STAFF/CLASS_ACCOUNT는 여기서 절대 만들어지지 않는다(signupStaff/ClassService.join과 동일한 경계). */
    private static final Set<Role> OAUTH_SIGNUP_ROLES = Set.of(Role.DIRECTOR, Role.PARENT, Role.TUTOR);

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final AccountDeletionFeedbackRepository accountDeletionFeedbackRepository;
    private final AuthValidator validator;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final GoogleOAuthVerifier googleOAuthVerifier;
    private final KakaoOAuthVerifier kakaoOAuthVerifier;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
            AppUserRepository userRepository, PasswordResetTokenRepository passwordResetTokenRepository,
            AccountDeletionFeedbackRepository accountDeletionFeedbackRepository,
            AuthValidator validator, PasswordEncoder passwordEncoder, JwtService jwtService,
            GoogleOAuthVerifier googleOAuthVerifier, KakaoOAuthVerifier kakaoOAuthVerifier) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.accountDeletionFeedbackRepository = accountDeletionFeedbackRepository;
        this.validator = validator;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.googleOAuthVerifier = googleOAuthVerifier;
        this.kakaoOAuthVerifier = kakaoOAuthVerifier;
    }

    @Transactional
    public AuthResponse signupOrganizationOwner(SignupOrganizationOwnerRequest request) {
        return createAccount(Role.DIRECTOR, request);
    }

    /**
     * 반 코드 없이 가입하는 "독립" 학부모 - 아이가 제휴 유치원에 다니지 않는 경우다. organization/
     * classGroup 둘 다 null이며(AppUser.java 참고), 접근은 EntitlementService의 개인 구독 경로로만
     * 판단된다(기관 구독 경로는 orgId가 없으니 자동으로 매치되지 않는다). 반 코드로 가입하는
     * 학부모는 여전히 ClassService.join()을 통해서만 만들어진다.
     */
    @Transactional
    public AuthResponse signupParent(SignupOrganizationOwnerRequest request) {
        return createAccount(Role.PARENT, request);
    }

    /**
     * 방문 선생님 - 가정을 방문해 1:1 수업을 진행하는 셀프서비스 고객 역할이다. organization/
     * classGroup 둘 다 null이며, tutor 패키지의 TutorStudent가 이 계정이 등록한 학생을 소유한다.
     */
    @Transactional
    public AuthResponse signupTutor(SignupOrganizationOwnerRequest request) {
        return createAccount(Role.TUTOR, request);
    }

    /**
     * STAFF 계정을 발급한다 - 내부 콘텐츠 제작 역할이다. AuthController의 X-Admin-Token으로
     * 게이트된 경로를 통해서만 도달할 수 있으며, 고객 대상 회원가입 폼으로는 절대 도달할 수
     * 없다; 이 분리가 왜 중요한지는 Role.java를 참고.
     */
    @Transactional
    public AuthResponse signupStaff(SignupOrganizationOwnerRequest request) {
        return createAccount(Role.STAFF, request);
    }

    private AuthResponse createAccount(Role role, SignupOrganizationOwnerRequest request) {
        validator.validateSignup(request);
        String loginId = request.loginId().trim().toLowerCase();
        String email = request.email().trim().toLowerCase();
        AppUser user = AppUser.builder()
                .role(role)
                .loginId(loginId)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName().trim())
                .createdAt(Instant.now())
                .build();
        try {
            // save가 아니라 saveAndFlush를 쓴다: 클라이언트/사전 생성된 @UuidGenerator id를 쓰면
            // Hibernate가 INSERT를 트랜잭션 커밋 시점까지 지연시킬 수 있는데, 그 시점은 이미 이
            // 메서드(그리고 그 catch 블록)가 반환된 이후다 - 여기서 flush를 강제하면 제약 조건
            // 위반이 동기적으로 드러나서 실제로 catch할 수 있게 된다.
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException alreadyRegistered) {
            throw ApiException.contractError(ErrorCode.LOGIN_ID_ALREADY_REGISTERED, "이미 사용 중인 아이디예요.");
        }
        return issueResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        validator.validateLogin(request);
        AppUser user = userRepository.findByLoginId(request.loginId().trim().toLowerCase())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않아요."));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.contractError(ErrorCode.INVALID_CREDENTIALS, "아이디 또는 비밀번호가 올바르지 않아요.");
        }
        return issueResponse(user);
    }

    /**
     * 이미 이 provider+subject로 연결된 계정이 있으면 로그인(role은 무시), 없으면 role이 있어야만
     * 새 계정을 만든다(role 없이 처음 보는 identity가 들어오면 - 예: SignInStep처럼 role을 아예
     * 모르는 로그인 화면에서 온 요청 - OAUTH_ROLE_REQUIRED로 "먼저 가입해 주세요"라고 안내한다).
     *
     * <p>같은 이메일의 비밀번호 계정이 이미 있어도 자동으로 연결하지 않는다 - provider가 준
     * email claim만으로 계정을 합치면, 검증되지 않은(또는 검증 방식이 다른) 이메일 소유권을
     * 근거로 계정을 탈취할 수 있는 통로가 생긴다. 대신 명확히 실패시켜 기존 로그인으로 안내한다.
     */
    @Transactional
    public AuthResponse loginOrSignupWithOAuth(OAuthProvider provider, OAuthLoginRequest request) {
        if (request.token() == null || request.token().isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "로그인 정보를 확인하지 못했어요.");
        }
        OAuthIdentity identity = resolveIdentity(provider, request.token());

        Optional<AppUser> existing = userRepository.findByOauthProviderAndOauthSubject(provider, identity.subject());
        if (existing.isPresent()) {
            return issueResponse(existing.get());
        }

        if (request.role() == null || !OAUTH_SIGNUP_ROLES.contains(request.role())) {
            throw ApiException.contractError(ErrorCode.OAUTH_ROLE_REQUIRED, "가입할 역할을 먼저 선택해 주세요.");
        }

        String loginId = resolveLoginId(provider, identity);
        if (userRepository.existsByLoginId(loginId)) {
            throw ApiException.contractError(
                    ErrorCode.OAUTH_EMAIL_ALREADY_REGISTERED, "이미 이메일로 가입된 계정이에요. 아이디/비밀번호로 로그인해 주세요.", 409);
        }

        String displayName = identity.displayName() != null && !identity.displayName().isBlank()
                ? identity.displayName().trim()
                : "새 사용자";
        AppUser user = AppUser.builder()
                .role(request.role())
                .loginId(loginId)
                .email(identity.email() != null && !identity.email().isBlank() ? identity.email().trim().toLowerCase() : null)
                .oauthProvider(provider)
                .oauthSubject(identity.subject())
                .displayName(displayName)
                .createdAt(Instant.now())
                .build();
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException alreadyRegistered) {
            throw ApiException.contractError(ErrorCode.LOGIN_ID_ALREADY_REGISTERED, "이미 등록된 계정이에요.");
        }
        return issueResponse(user);
    }

    private record OAuthIdentity(String subject, String email, String displayName) {}

    private OAuthIdentity resolveIdentity(OAuthProvider provider, String token) {
        return switch (provider) {
            case GOOGLE -> {
                GoogleOAuthVerifier.GoogleIdentity identity = googleOAuthVerifier.verify(token);
                yield new OAuthIdentity(identity.subject(), identity.email(), identity.displayName());
            }
            case KAKAO -> {
                KakaoOAuthVerifier.KakaoIdentity identity = kakaoOAuthVerifier.verify(token);
                yield new OAuthIdentity(identity.subject(), identity.email(), identity.displayName());
            }
        };
    }

    private String resolveLoginId(OAuthProvider provider, OAuthIdentity identity) {
        if (identity.email() != null && !identity.email().isBlank()) {
            return identity.email().trim().toLowerCase();
        }
        // 카카오는 이메일 제공에 동의하지 않은 사용자라면 이메일이 없을 수 있다 - loginId는
        // not-null unique라 provider:subject 조합으로 대체한다(사람이 직접 보거나 입력하는
        // 값이 아니다).
        return provider.name().toLowerCase() + ":" + identity.subject();
    }

    public UserSummary me(CurrentUser caller) {
        return UserSummary.of(requireActiveUser(caller.userId()));
    }

    @Transactional
    public UserSummary updateProfile(CurrentUser caller, UpdateProfileRequest request) {
        AppUser user = requireActiveUser(caller.userId());
        validator.validateDisplayName(request.displayName());
        user.setDisplayName(request.displayName().trim());
        // childName은 PARENT에게만 의미가 있다 - 다른 역할이 값을 보내도 조용히 무시한다(프론트가
        // 애초에 PARENT에게만 입력창을 보여주므로 여기서 에러로 되돌려줄 실익이 없다).
        if (user.getRole() == Role.PARENT) {
            String trimmed = request.childName() == null ? "" : request.childName().trim();
            if (trimmed.length() > 60) {
                throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "아이 이름이 너무 길어요.");
            }
            user.setChildName(trimmed.isEmpty() ? null : trimmed);
        }
        userRepository.save(user);
        return UserSummary.of(user);
    }

    @Transactional
    public void changePassword(CurrentUser caller, ChangePasswordRequest request) {
        AppUser user = requireActiveUser(caller.userId());
        if (user.getPasswordHash() == null
                || request.currentPassword() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.contractError(ErrorCode.INVALID_CREDENTIALS, "현재 비밀번호가 올바르지 않아요.", 401);
        }
        validator.validatePassword(request.newPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    /**
     * 소프트 삭제 - deletedAt을 채우고 loginId를 변형해 원래 이메일을 재가입에 다시 쓸 수 있게
     * 풀어준다. 탈퇴 사유는 계정이 사라지기 전에 먼저 저장한다(AccountDeletionFeedback).
     */
    @Transactional
    public void deleteAccount(CurrentUser caller, DeleteAccountRequest request) {
        String reasonCategory = request.reasonCategory() == null ? "" : request.reasonCategory().trim();
        if (!DELETE_REASON_CATEGORIES.contains(reasonCategory)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "탈퇴 사유를 선택해 주세요.");
        }
        String reasonDetail = request.reasonDetail() == null ? "" : request.reasonDetail().trim();
        if (reasonDetail.length() > MAX_REASON_DETAIL_LENGTH) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "입력한 내용이 너무 길어요.");
        }

        AppUser user = requireActiveUser(caller.userId());

        accountDeletionFeedbackRepository.save(AccountDeletionFeedback.builder()
                .userId(user.getId())
                .role(user.getRole().name())
                .reasonCategory(reasonCategory)
                .reasonDetail(reasonDetail.isEmpty() ? null : reasonDetail)
                .createdAt(Instant.now())
                .build());

        user.setDeletedAt(Instant.now());
        user.setLoginId("deleted:" + UUID.randomUUID() + ":" + user.getLoginId());
        user.setPasswordHash(null);
        userRepository.save(user);
    }

    private AppUser requireActiveUser(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
    }

    /**
     * loginId가 실제 계정과 일치하든 아니든, 호출자 입장에서는 항상 성공한 것처럼 보인다 -
     * 여기서 "그런 계정 없음" 응답을 준다면 누구든 어떤 이메일이 등록되어 있는지 조사할 수
     * 있게 된다.
     *
     * <p>아직 이메일 발송 공급자가 연결되어 있지 않으므로(Role.java 인근의 auth 작업 로그 참고)
     * 원본 토큰은 전달되는 대신 여기서 로그로 남는다 - 이는 정확히 이메일 연동이 대체하게 될
     * 이음매(seam)이다: 이 로그 라인을 실제 발송으로 바꾸기만 하면 되고, 토큰 생명주기의 나머지
     * 부분은 아무것도 바뀌지 않는다.
     */
    @Transactional
    public void requestPasswordReset(RequestPasswordResetRequest request) {
        if (request.loginId() == null || request.loginId().isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "아이디를 입력해 주세요.");
        }
        userRepository.findByLoginId(request.loginId().trim().toLowerCase()).ifPresent(user -> {
            String rawToken = randomToken();
            passwordResetTokenRepository.save(PasswordResetToken.builder()
                    .user(user)
                    .tokenHash(DigestUtil.sha256Hex(rawToken))
                    .expiresAt(Instant.now().plus(PASSWORD_RESET_TTL))
                    .createdAt(Instant.now())
                    .build());
            log.info("password-reset.token-issued userId={} token={} (no email provider configured - deliver manually until one is)",
                    user.getId(), rawToken);
        });
    }

    @Transactional
    public AuthResponse confirmPasswordReset(ConfirmPasswordResetRequest request) {
        if (request.token() == null || request.token().isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "재설정 링크가 올바르지 않아요.");
        }
        validator.validatePassword(request.newPassword());

        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(DigestUtil.sha256Hex(request.token()))
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.INVALID_PASSWORD_RESET_TOKEN, "재설정 링크가 올바르지 않거나 만료됐어요.", 410));
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.contractError(
                    ErrorCode.INVALID_PASSWORD_RESET_TOKEN, "재설정 링크가 올바르지 않거나 만료됐어요.", 410);
        }
        token.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(token);

        AppUser user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        return issueResponse(user);
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private AuthResponse issueResponse(AppUser user) {
        CurrentUser currentUser = new CurrentUser(
                user.getId(), user.getRole(),
                user.getOrganization() == null ? null : user.getOrganization().getId(),
                user.getClassGroup() == null ? null : user.getClassGroup().getId());
        return new AuthResponse(jwtService.issue(currentUser), UserSummary.of(user));
    }
}
