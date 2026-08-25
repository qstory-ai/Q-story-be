package com.qstory.backend.identity.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.ConfirmPasswordResetRequest;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.RequestPasswordResetRequest;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.dto.UserSummary;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.entity.PasswordResetToken;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.repository.PasswordResetTokenRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.util.AuthValidator;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final AuthValidator validator;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
            AppUserRepository userRepository, PasswordResetTokenRepository passwordResetTokenRepository,
            AuthValidator validator, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.validator = validator;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse signupOrganizationOwner(SignupOrganizationOwnerRequest request) {
        return createAccount(Role.DIRECTOR, request);
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
        String loginId = request.email().trim().toLowerCase();
        AppUser user = AppUser.builder()
                .role(role)
                .loginId(loginId)
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
            throw ApiException.contractError(ErrorCode.LOGIN_ID_ALREADY_REGISTERED, "이미 등록된 이메일이에요.");
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

    public UserSummary me(CurrentUser caller) {
        AppUser user = userRepository.findById(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        return UserSummary.of(user);
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
