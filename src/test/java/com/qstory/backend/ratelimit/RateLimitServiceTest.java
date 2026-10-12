package com.qstory.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitServiceTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private RateLimitService service(Map<String, RateLimitProperties.Limit> limits, int ttsDaily, Instant now) {
        RateLimitProperties props = new RateLimitProperties(true, "", "", ttsDaily, limits, 1000);
        return new RateLimitService(props, Clock.fixed(now, ZoneOffset.UTC), nanos::get);
    }

    private static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/narrations/stream");
        request.setRemoteAddr(ip);
        return request;
    }

    private static void logIn(UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(
                new CurrentUser(userId, Role.PARENT, null), null, List.of(new SimpleGrantedAuthority("ROLE_PARENT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void perClientLimitIsKeyedByAddressForAnonymousAndByAccountWhenLoggedIn() {
        RateLimitService service = service(Map.of("tts", new RateLimitProperties.Limit(2, Duration.ofMinutes(10))), 80,
                Instant.now());
        service.enforce(from("1.1.1.1"), RateLimitRule.TTS);
        service.enforce(from("1.1.1.1"), RateLimitRule.TTS);
        assertThatThrownBy(() -> service.enforce(from("1.1.1.1"), RateLimitRule.TTS))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.retryAfterSeconds()).isEqualTo(300);
                    assertThat(e.safeDetail()).contains("다시 말해 줘");
                });
        // 다른 주소, 같은 주소의 로그인한 계정은 따로 센다.
        service.enforce(from("2.2.2.2"), RateLimitRule.TTS);
        logIn(UUID.randomUUID());
        service.enforce(from("1.1.1.1"), RateLimitRule.TTS);
    }

    @Test
    void anonymousLiveTtsHasAServerWideDailyCapThatResetsAtPacificMidnight() {
        // 2026-10-12 23:00 PDT = 2026-10-13 06:00 UTC - 한 시간 뒤 초기화.
        Instant now = Instant.parse("2026-10-13T06:00:00Z");
        RateLimitService service = service(
                Map.of("tts-anonymous-hourly", new RateLimitProperties.Limit(100, Duration.ofHours(1))), 3, now);
        for (int i = 0; i < 3; i++) service.enforceAnonymousLiveTts(from("10.0.0." + i));
        assertThatThrownBy(() -> service.enforceAnonymousLiveTts(from("10.0.0.99")))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(3600));
        // 로그인한 UT 사용자는 익명 총량에 걸리지 않는다.
        logIn(UUID.randomUUID());
        service.enforceAnonymousLiveTts(from("10.0.0.99"));
    }

    @Test
    void anonymousLiveTtsHourlyCapIsSharedAcrossAddresses() {
        RateLimitService service = service(
                Map.of("tts-anonymous-hourly", new RateLimitProperties.Limit(2, Duration.ofHours(1))), 80, Instant.now());
        service.enforceAnonymousLiveTts(from("10.0.0.1"));
        service.enforceAnonymousLiveTts(from("10.0.0.2"));
        assertThatThrownBy(() -> service.enforceAnonymousLiveTts(from("10.0.0.3")))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void failureCounterBlocksBeforeComparingOnceExhausted() {
        RateLimitService service = service(
                Map.of("admin-token-failure", new RateLimitProperties.Limit(2, Duration.ofHours(1))), 80, Instant.now());
        MockHttpServletRequest request = from("6.6.6.6");
        service.requireNotBlocked(request, RateLimitRule.ADMIN_TOKEN_FAILURE);
        service.recordFailure(request, RateLimitRule.ADMIN_TOKEN_FAILURE);
        service.requireNotBlocked(request, RateLimitRule.ADMIN_TOKEN_FAILURE);
        service.recordFailure(request, RateLimitRule.ADMIN_TOKEN_FAILURE);
        assertThatThrownBy(() -> service.requireNotBlocked(from("6.6.6.6"), RateLimitRule.ADMIN_TOKEN_FAILURE))
                .isInstanceOf(RateLimitedException.class);
        service.requireNotBlocked(from("7.7.7.7"), RateLimitRule.ADMIN_TOKEN_FAILURE);
    }

    @Test
    void disabledNeverBlocks() {
        RateLimitProperties props = new RateLimitProperties(false, "", "", 0,
                Map.of("tts", new RateLimitProperties.Limit(1, Duration.ofHours(1))), 1000);
        RateLimitService service = new RateLimitService(props, Clock.systemUTC(), nanos::get);
        for (int i = 0; i < 5; i++) {
            service.enforce(from("1.1.1.1"), RateLimitRule.TTS);
            service.enforceAnonymousLiveTts(from("1.1.1.1"));
        }
    }

    @Test
    void configuredLimitsOverrideDefaults() {
        RateLimitProperties props = new RateLimitProperties(null, null, null, null,
                Map.of("stt", new RateLimitProperties.Limit(7, Duration.ofMinutes(2))), null);
        assertThat(props.enabled()).isTrue();
        assertThat(props.requestsFor(RateLimitRule.STT)).isEqualTo(7);
        assertThat(props.windowFor(RateLimitRule.STT)).isEqualTo(Duration.ofMinutes(2));
        assertThat(props.requestsFor(RateLimitRule.SIGNUP)).isEqualTo(RateLimitRule.SIGNUP.defaultRequests());
        assertThat(props.ttsAnonymousDaily()).isEqualTo(80);
    }

    @Test
    void logKeysAreHashedNotRawAddresses() {
        String hashed = RateLimitService.hashKey("ip:203.0.113.7");
        assertThat(hashed).hasSize(12).doesNotContain("203");
        assertThat(RateLimitService.hashKey("ip:203.0.113.7")).isEqualTo(hashed);
        assertThat(RateLimitService.hashKey("global")).isEqualTo("global");
    }

    @Test
    void loginMessageSaysHowLongToWait() {
        assertThat(RateLimitService.safeDetail(RateLimitRule.LOGIN_ACCOUNT, 600)).isEqualTo(
                "로그인 시도가 너무 많아요. 10분 뒤에 다시 시도해 주세요.");
        assertThat(RateLimitService.safeDetail(RateLimitRule.SIGNUP, 30)).contains("잠시 후");
        assertThat(RateLimitService.safeDetail(RateLimitRule.PASSWORD_RESET_REQUEST, 3600)).contains("1시간 뒤에");
    }
}
