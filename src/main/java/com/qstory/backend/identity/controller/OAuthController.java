package com.qstory.backend.identity.controller;

import com.qstory.backend.identity.OAuthProvider;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.dto.OAuthLoginRequest;
import com.qstory.backend.identity.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구글/카카오 소셜 로그인·가입 - AuthController와 분리해 둔다: 이 엔드포인트들의 핵심은 비밀번호
 * 검증이 아니라 외부 provider 토큰 검증(GoogleOAuthVerifier/KakaoOAuthVerifier)이라 관심사가
 * 다르다. role은 이 provider+subject로 처음 가입할 때만 쓰이고(AuthService.
 * loginOrSignupWithOAuth 참고), 이미 연결된 계정으로 로그인할 때는 무시된다.
 */
@Tag(name = "Auth", description = "Google/Kakao social login and first-time signup")
@RestController
public class OAuthController {

    private final AuthService authService;

    public OAuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Sign in or sign up with Google",
            description = "token is the id_token from Google Identity Services. role is required only for a brand-new account.")
    @PostMapping("/v1/auth/oauth/google")
    public AuthResponse google(@RequestBody OAuthLoginRequest request) {
        return authService.loginOrSignupWithOAuth(OAuthProvider.GOOGLE, request);
    }

    @Operation(summary = "Sign in or sign up with Kakao",
            description = "token is the access token from the Kakao JS SDK. role is required only for a brand-new account.")
    @PostMapping("/v1/auth/oauth/kakao")
    public AuthResponse kakao(@RequestBody OAuthLoginRequest request) {
        return authService.loginOrSignupWithOAuth(OAuthProvider.KAKAO, request);
    }
}
