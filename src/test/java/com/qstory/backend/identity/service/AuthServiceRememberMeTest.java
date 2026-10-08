package com.qstory.backend.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.OAuthProvider;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.ConsentPayload;
import com.qstory.backend.identity.dto.LoginRequest;
import com.qstory.backend.identity.dto.OAuthLoginRequest;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.oauth.GoogleOAuthVerifier;
import com.qstory.backend.identity.util.AuthValidator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 로그인 유지(rememberMe) - 로그인/소셜 로그인/회원가입/refresh가 어떤 모드의 토큰을 발급하는지. 실제 JwtService로 토큰을 만든다. */
class AuthServiceRememberMeTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    private static final ConsentPayload OK = new ConsentPayload("2026-10-v1", true, true, true);

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final GoogleOAuthVerifier google = mock(GoogleOAuthVerifier.class);
    private final JwtService jwt = jwtService();
    private final AuthService service = new AuthService(
            users, null, null, mock(AuthValidator.class), passwords, jwt,
            google, null, null, null, null, mock(UserSummaryFactory.class), null, null, mock(ConsentService.class));

    private static JwtService jwtService() {
        AppProperties config = mock(AppProperties.class);
        when(config.auth()).thenReturn(new AppProperties.Auth(SECRET, 90, 12));
        return new JwtService(config);
    }

    private AppUser passwordUser() {
        AppUser user = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).loginId("teacher").passwordHash("hash").build();
        when(users.findByLoginId("teacher")).thenReturn(Optional.of(user));
        when(passwords.matches("pw", "hash")).thenReturn(true);
        return user;
    }

    private boolean rememberMeOf(AuthResponse response) {
        return jwt.verify(response.token()).orElseThrow().rememberMe();
    }

    @Test
    void loginRespectsRememberMeFlag() {
        passwordUser();
        assertTrue(rememberMeOf(service.login(new LoginRequest("teacher", "pw", true))));
        assertFalse(rememberMeOf(service.login(new LoginRequest("teacher", "pw", false))));
    }

    /** rememberMe를 모르는 예전 앱은 이전처럼 장기 토큰을 받는다. */
    @Test
    void loginWithoutFlagDefaultsToRememberMe() {
        passwordUser();
        assertTrue(rememberMeOf(service.login(new LoginRequest("teacher", "pw"))));
    }

    @Test
    void existingOAuthAccountRespectsRememberMeFlag() {
        when(google.verify("tok")).thenReturn(new GoogleOAuthVerifier.GoogleIdentity("sub", "a@b.co", "이름"));
        AppUser user = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).loginId("a@b.co").build();
        when(users.findByOauthProviderAndOauthSubject(OAuthProvider.GOOGLE, "sub")).thenReturn(Optional.of(user));

        assertFalse(rememberMeOf(service.loginOrSignupWithOAuth(
                OAuthProvider.GOOGLE, new OAuthLoginRequest("tok", null, null, false))));
        assertTrue(rememberMeOf(service.loginOrSignupWithOAuth(
                OAuthProvider.GOOGLE, new OAuthLoginRequest("tok", null, null))));
    }

    @Test
    void signupIssuesRememberMeToken() {
        when(users.saveOrThrowDuplicate(any(), anyString())).thenAnswer(i -> {
            AppUser saved = i.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        AuthResponse response = service.signupParent(
                new SignupOrganizationOwnerRequest("loginid", "a@b.co", "password1", "이름", OK));
        assertTrue(rememberMeOf(response));
    }

    @Test
    void refreshKeepsMode() {
        AppUser user = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).build();
        when(users.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));

        assertFalse(rememberMeOf(service.refresh(new CurrentUser(user.getId(), Role.TUTOR, null, false))));
        assertTrue(rememberMeOf(service.refresh(new CurrentUser(user.getId(), Role.TUTOR, null, true))));
    }

    @Test
    void refreshForDeletedAccountIs401() {
        UUID userId = UUID.randomUUID();
        when(users.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());
        ApiException e = assertThrows(ApiException.class,
                () -> service.refresh(new CurrentUser(userId, Role.PARENT, null, true)));
        assertEquals(ErrorCode.UNAUTHENTICATED, e.code());
    }

    /** 요청 본문 역직렬화 - 보조 생성자가 있어도 Jackson이 정식 생성자를 쓰는지, 필드가 없으면 null인지. */
    @Test
    void requestBodiesDeserializeWithAndWithoutRememberMe() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(Boolean.FALSE, mapper.readValue(
                "{\"loginId\":\"a\",\"password\":\"b\",\"rememberMe\":false}", LoginRequest.class).rememberMe());
        assertNull(mapper.readValue("{\"loginId\":\"a\",\"password\":\"b\"}", LoginRequest.class).rememberMe());
        assertEquals(Boolean.FALSE, mapper.readValue(
                "{\"token\":\"t\",\"rememberMe\":false}", OAuthLoginRequest.class).rememberMe());
        assertNull(mapper.readValue("{\"token\":\"t\"}", OAuthLoginRequest.class).rememberMe());
    }
}
