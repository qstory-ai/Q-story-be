package com.qstory.backend.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.ChangePasswordRequest;
import com.qstory.backend.identity.dto.ConfirmPasswordResetRequest;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.RequestPasswordResetRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.entity.PasswordResetToken;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.repository.PasswordResetTokenRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.security.TokenVersionCheck;
import com.qstory.backend.identity.util.AuthValidator;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 보안 점검 M4·L4·L11 - 비밀번호 변경·재설정이 지금까지 발급한 토큰을 무효로 하는지(token_version), refresh가 처음 로그인한 뒤
 * 180일까지만 이어지는지, 탈퇴한 계정은 로그인·재설정 대상이 아닌지.
 */
class AuthServiceTokenRevocationTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordResetTokenRepository resetTokens = mock(PasswordResetTokenRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final TokenVersionCheck tokenVersions = mock(TokenVersionCheck.class);
    private final SecureTokenGenerator tokenGenerator = mock(SecureTokenGenerator.class);
    private final JwtService jwt = jwtService();
    private final AuthService service = new AuthService(
            users, resetTokens, null, mock(AuthValidator.class), passwords, jwt, null, null, tokenGenerator, null, null,
            mock(UserSummaryFactory.class), null, null, mock(ConsentService.class), tokenVersions, null);

    private static JwtService jwtService() {
        AppProperties config = mock(AppProperties.class);
        when(config.auth()).thenReturn(new AppProperties.Auth(SECRET, 90, 12));
        return new JwtService(config);
    }

    private AppUser user(int tokenVersion) {
        AppUser user = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).loginId("mom").passwordHash("old-hash")
                .tokenVersion(tokenVersion).build();
        when(users.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    private CurrentUser verified(AuthResponse response) {
        return jwt.verify(response.token()).orElseThrow();
    }

    @Test
    void issuedTokenCarriesTheAccountsTokenVersion() {
        AppUser user = user(3);
        when(users.findByLoginIdAndDeletedAtIsNull("mom")).thenReturn(Optional.of(user));
        when(passwords.matches("pw", "old-hash")).thenReturn(true);

        assertEquals(3, verified(service.login(new LoginRequest("mom", "pw", true))).tokenVersion());
    }

    @Test
    void changingPasswordRevokesEarlierTokensAndHandsBackANewOne() {
        AppUser user = user(2);
        when(passwords.matches("old", "old-hash")).thenReturn(true);
        when(passwords.encode("new-password1")).thenReturn("new-hash");
        CurrentUser caller = new CurrentUser(user.getId(), Role.PARENT, null, false, 2, Instant.now());

        AuthResponse response = service.changePassword(caller, new ChangePasswordRequest("old", "new-password1"));

        assertEquals(3, user.getTokenVersion());
        verify(tokenVersions).evict(user.getId());
        CurrentUser renewed = verified(response);
        assertEquals(3, renewed.tokenVersion());
        assertEquals(false, renewed.rememberMe(), "지금 기기의 로그인 유지 모드는 그대로");
    }

    @Test
    void passwordResetRevokesEarlierTokens() {
        AppUser user = user(0);
        when(resetTokens.findByTokenHash(DigestUtil.sha256Hex("reset-token"))).thenReturn(Optional.of(
                PasswordResetToken.builder().user(user).tokenHash("h").expiresAt(Instant.now().plusSeconds(600))
                        .createdAt(Instant.now()).build()));
        when(passwords.encode("new-password1")).thenReturn("new-hash");

        AuthResponse response = service.confirmPasswordReset(new ConfirmPasswordResetRequest("reset-token", "new-password1"));

        assertEquals(1, user.getTokenVersion());
        verify(tokenVersions).evict(user.getId());
        assertEquals(1, verified(response).tokenVersion());
    }

    /** 보안 점검 L4: 탈퇴한 계정의 남은 재설정 링크로는 비밀번호를 다시 쓰지 못한다. */
    @Test
    void passwordResetOfDeletedAccountIsRefused() {
        AppUser deleted = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).deletedAt(Instant.now()).build();
        when(resetTokens.findByTokenHash(DigestUtil.sha256Hex("reset-token"))).thenReturn(Optional.of(
                PasswordResetToken.builder().user(deleted).tokenHash("h").expiresAt(Instant.now().plusSeconds(600))
                        .createdAt(Instant.now()).build()));

        ApiException e = assertThrows(ApiException.class,
                () -> service.confirmPasswordReset(new ConfirmPasswordResetRequest("reset-token", "new-password1")));
        assertEquals(ErrorCode.INVALID_PASSWORD_RESET_TOKEN, e.code());
        verify(users, never()).save(any());
    }

    /** 보안 점검 L4: 로그인·재설정 요청은 탈퇴하지 않은 계정에서만 찾는다(없으면 없는 계정과 같다). */
    @Test
    void deletedAccountsAreNotFoundForLoginOrResetRequests() {
        when(users.findByLoginIdAndDeletedAtIsNull("gone")).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.login(new LoginRequest("gone", "pw", true)));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, e.code());

        service.requestPasswordReset(new RequestPasswordResetRequest("gone"));
        verify(resetTokens, never()).save(any());
    }

    @Test
    void refreshKeepsTheOriginalLoginTime() {
        AppUser user = user(0);
        Instant loggedInAt = Instant.now().minus(Duration.ofDays(100)).truncatedTo(ChronoUnit.SECONDS);

        CurrentUser renewed = verified(service.refresh(new CurrentUser(user.getId(), Role.PARENT, null, true, 0, loggedInAt)));

        assertEquals(loggedInAt, renewed.authTime());
    }

    @Test
    void refreshIsRefusedLongAfterLogin() {
        AppUser user = user(0);
        Instant loggedInAt = Instant.now().minus(AuthService.MAX_LOGIN_AGE).minus(Duration.ofDays(1));

        ApiException e = assertThrows(ApiException.class,
                () -> service.refresh(new CurrentUser(user.getId(), Role.PARENT, null, true, 0, loggedInAt)));
        assertEquals(ErrorCode.UNAUTHENTICATED, e.code());
        assertEquals(401, e.statusCode());
    }
}
