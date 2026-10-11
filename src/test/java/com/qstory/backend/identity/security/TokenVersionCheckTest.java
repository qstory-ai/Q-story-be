package com.qstory.backend.identity.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** 보안 점검 M4 - 비밀번호를 바꿨거나 탈퇴한 계정의 토큰은 서명·만료가 맞아도 인증되지 않는다. */
class TokenVersionCheckTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AtomicLong clock = new AtomicLong();
    private final TokenVersionCheck check = new TokenVersionCheck(jdbc, clock::get);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void accountVersion(Integer... versions) {
        var stub = when(jdbc.queryForList(anyString(), eq(Integer.class), eq(userId)));
        List<Integer> first = versions[0] == null ? List.of() : List.of(versions[0]);
        stub = stub.thenReturn(first);
        for (int i = 1; i < versions.length; i++) {
            stub = stub.thenReturn(versions[i] == null ? List.of() : List.of(versions[i]));
        }
    }

    @Test
    void tokenOfTheCurrentVersionPasses() {
        accountVersion(2);
        assertTrue(check.isCurrent(new CurrentUser(userId, Role.PARENT, null, true, 2, null)));
        assertFalse(check.isCurrent(new CurrentUser(userId, Role.PARENT, null, true, 1, null)));
    }

    @Test
    void deletedAccountsTokenFails() {
        accountVersion((Integer) null);
        assertFalse(check.isCurrent(new CurrentUser(userId, Role.PARENT, null, true, 0, null)));
    }

    @Test
    void lookupIsCachedBrieflyAndEvictable() {
        accountVersion(0, 1);
        CurrentUser old = new CurrentUser(userId, Role.PARENT, null, true, 0, null);
        assertTrue(check.isCurrent(old));
        assertTrue(check.isCurrent(old), "30초 안에는 캐시");
        verify(jdbc, times(1)).queryForList(anyString(), eq(Integer.class), eq(userId));

        check.evict(userId);
        assertFalse(check.isCurrent(old), "올린 뒤 비우면 바로 막힌다");
    }

    @Test
    void cacheExpires() {
        accountVersion(0, 1);
        CurrentUser old = new CurrentUser(userId, Role.PARENT, null, true, 0, null);
        assertTrue(check.isCurrent(old));
        clock.addAndGet(TokenVersionCheck.TTL.toNanos() + 1);
        assertFalse(check.isCurrent(old));
    }

    /** 필터: 버전이 맞지 않는 토큰은 익명으로 둔다(거절은 엔드포인트가 401로). */
    @Test
    void filterLeavesRevokedTokenAnonymous() throws Exception {
        AppProperties config = mock(AppProperties.class);
        when(config.auth()).thenReturn(new AppProperties.Auth(SECRET, 90, 12));
        JwtService jwt = new JwtService(config);
        JwtAuthFilter filter = new JwtAuthFilter(jwt, check);
        accountVersion(1);

        MockHttpServletRequest revoked = new MockHttpServletRequest("GET", "/v1/auth/me");
        revoked.addHeader("Authorization", "Bearer " + jwt.issue(new CurrentUser(userId, Role.PARENT, null, true, 0, null)));
        filter.doFilter(revoked, new MockHttpServletResponse(), mock(FilterChain.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        MockHttpServletRequest current = new MockHttpServletRequest("GET", "/v1/auth/me");
        current.addHeader("Authorization", "Bearer " + jwt.issue(new CurrentUser(userId, Role.PARENT, null, true, 1, null)));
        filter.doFilter(current, new MockHttpServletResponse(), mock(FilterChain.class));
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof CurrentUser);
    }
}
