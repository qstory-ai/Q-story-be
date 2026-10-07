package com.qstory.backend.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.OAuthProvider;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.ConsentPayload;
import com.qstory.backend.identity.dto.OAuthLoginRequest;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.oauth.GoogleOAuthVerifier;
import com.qstory.backend.identity.util.AuthValidator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceConsentTest {

    private static final ConsentPayload OK = new ConsentPayload("2026-10-v1", true, true, true);

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final GoogleOAuthVerifier google = mock(GoogleOAuthVerifier.class);
    private final ConsentService consents = mock(ConsentService.class);
    private final AuthService service = new AuthService(
            users, null, null, mock(AuthValidator.class), mock(PasswordEncoder.class), mock(JwtService.class),
            google, null, null, null, null, null, null, mock(UserSummaryFactory.class), null, null, consents);

    private SignupOrganizationOwnerRequest signup(ConsentPayload consent) {
        return new SignupOrganizationOwnerRequest("loginid", "a@b.co", "password1", "이름", consent);
    }

    private void consentsRejectMissing() {
        doThrow(ApiException.contractError(ErrorCode.CONSENT_REQUIRED, "x")).when(consents).requireSignupConsents(any());
    }

    @Test
    void signupWithoutConsentIsRejectedBeforeSaving() {
        consentsRejectMissing();
        ApiException e = assertThrows(ApiException.class, () -> service.signupParent(signup(null)));
        assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
        verify(users, never()).saveOrThrowDuplicate(any(), anyString());
    }

    @Test
    void signupWithConsentRecordsIt() {
        when(users.saveOrThrowDuplicate(any(), anyString())).thenAnswer(i -> i.getArgument(0));
        service.signupParent(signup(OK));
        verify(consents).recordSignup(any(AppUser.class), eq(OK), eq("SIGNUP"));
    }

    @Test
    void existingOAuthAccountLogsInWithoutConsents() {
        when(google.verify("tok")).thenReturn(new GoogleOAuthVerifier.GoogleIdentity("sub", "a@b.co", "이름"));
        AppUser existing = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).build();
        when(users.findByOauthProviderAndOauthSubject(OAuthProvider.GOOGLE, "sub")).thenReturn(Optional.of(existing));
        service.loginOrSignupWithOAuth(OAuthProvider.GOOGLE, new OAuthLoginRequest("tok", null, null));
        verify(consents, never()).requireSignupConsents(any());
    }

    @Test
    void newOAuthAccountRequiresConsents() {
        consentsRejectMissing();
        when(google.verify("tok")).thenReturn(new GoogleOAuthVerifier.GoogleIdentity("sub", "a@b.co", "이름"));
        when(users.findByOauthProviderAndOauthSubject(OAuthProvider.GOOGLE, "sub")).thenReturn(Optional.empty());
        ApiException e = assertThrows(ApiException.class, () -> service.loginOrSignupWithOAuth(
                OAuthProvider.GOOGLE, new OAuthLoginRequest("tok", Role.PARENT, null)));
        assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
        verify(users, never()).saveOrThrowDuplicate(any(), anyString());
    }

    @Test
    void newOAuthAccountRecordsConsents() {
        when(google.verify("tok")).thenReturn(new GoogleOAuthVerifier.GoogleIdentity("sub", "a@b.co", "이름"));
        when(users.findByOauthProviderAndOauthSubject(OAuthProvider.GOOGLE, "sub")).thenReturn(Optional.empty());
        when(users.saveOrThrowDuplicate(any(), anyString())).thenAnswer(i -> i.getArgument(0));
        service.loginOrSignupWithOAuth(OAuthProvider.GOOGLE, new OAuthLoginRequest("tok", Role.PARENT, OK));
        verify(consents).recordSignup(any(AppUser.class), eq(OK), eq("OAUTH_SIGNUP"));
    }
}
