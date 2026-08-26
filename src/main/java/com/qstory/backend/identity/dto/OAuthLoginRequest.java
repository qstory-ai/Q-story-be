package com.qstory.backend.identity.dto;

import com.qstory.backend.identity.Role;

/**
 * token은 provider마다 의미가 다르다 - 구글은 Google Identity Services가 발급한 id_token,
 * 카카오는 카카오 JS SDK가 발급한 access token(AuthService.loginOrSignupWithOAuth 참고).
 * role은 이 provider+subject로 처음 가입하는 경우에만 필요하고, 이미 연결된 계정으로
 * 로그인할 때는 무시된다.
 */
public record OAuthLoginRequest(String token, Role role) {}
