package com.qstory.backend.identity.security;

import com.qstory.backend.identity.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * 검증된 JWT의 claim에서 확정된 프로세스 내(in-process) 아이덴티티 - role·orgId 때문에 DB를 다시 조회하지는 않는다. orgId는 DIRECTOR에게만 있다.
 * rememberMe는 이 토큰이 "로그인 유지"로 발급됐는지다 - 토큰을 다시 발급할 때(refresh, 유치원 등록 등) 같은 수명 모드를 유지하는 데 쓴다.
 *
 * <p>tokenVersion은 발급 당시 app_user.token_version이다(078) - 비밀번호 변경·재설정·탈퇴로 계정 값이 올라가면 이 토큰은
 * JwtAuthFilter에서 더 이상 인증되지 않는다. authTime은 처음 로그인한 시각이다 - refresh로 새 토큰을 받아도 그대로 이어지고,
 * 너무 오래되면 refresh를 거절해 다시 로그인하게 한다(AuthService.MAX_LOGIN_AGE). null이면 지금 발급하는 토큰이 첫 로그인이다.
 */
public record CurrentUser(UUID userId, Role role, UUID orgId, boolean rememberMe, int tokenVersion, Instant authTime) {

    /** 로그인 유지 모드로 취급한다 - 회원가입처럼 모드를 따로 고르지 않는 발급 경로의 기본값이다. */
    public CurrentUser(UUID userId, Role role, UUID orgId) {
        this(userId, role, orgId, true);
    }

    public CurrentUser(UUID userId, Role role, UUID orgId, boolean rememberMe) {
        this(userId, role, orgId, rememberMe, 0, null);
    }
}
