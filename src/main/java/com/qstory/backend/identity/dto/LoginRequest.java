package com.qstory.backend.identity.dto;

/** rememberMe가 없으면(예전 앱) 로그인 유지로 본다 - AuthService.login 참고. */
public record LoginRequest(String loginId, String password, Boolean rememberMe) {

    public LoginRequest(String loginId, String password) {
        this(loginId, password, null);
    }
}
