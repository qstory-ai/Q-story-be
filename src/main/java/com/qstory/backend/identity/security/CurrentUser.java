package com.qstory.backend.identity.security;

import com.qstory.backend.identity.Role;
import java.util.UUID;

/**
 * 검증된 JWT의 claim에서 확정된 프로세스 내(in-process) 아이덴티티 - 이 필드들 때문에 DB를 다시 조회하는 일은 없다. orgId는 DIRECTOR에게만 있다.
 * rememberMe는 이 토큰이 "로그인 유지"로 발급됐는지다 - 토큰을 다시 발급할 때(refresh, 유치원 등록 등) 같은 수명 모드를 유지하는 데 쓴다.
 */
public record CurrentUser(UUID userId, Role role, UUID orgId, boolean rememberMe) {

    /** 로그인 유지 모드로 취급한다 - 회원가입처럼 모드를 따로 고르지 않는 발급 경로의 기본값이다. */
    public CurrentUser(UUID userId, Role role, UUID orgId) {
        this(userId, role, orgId, true);
    }
}
