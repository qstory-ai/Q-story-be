package com.qstory.backend.identity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtService jwt = service(90, 12);

    private static JwtService service(long rememberDays, long sessionHours) {
        AppProperties config = mock(AppProperties.class);
        when(config.auth()).thenReturn(new AppProperties.Auth(SECRET, rememberDays, sessionHours));
        return new JwtService(config);
    }

    private static Claims claims(String token) {
        return Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token).getPayload();
    }

    private static Duration lifetime(Claims claims) {
        return Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
    }

    @Test
    void rememberMeTokenLivesRememberMeTtlDaysAndCarriesClaim() {
        String token = jwt.issue(new CurrentUser(UUID.randomUUID(), Role.PARENT, null, true));
        Claims claims = claims(token);
        assertEquals(Duration.ofDays(90), lifetime(claims));
        assertEquals(Boolean.TRUE, claims.get("rm", Boolean.class));
    }

    @Test
    void sessionTokenLivesSessionTtlHoursAndCarriesClaim() {
        String token = jwt.issue(new CurrentUser(UUID.randomUUID(), Role.TUTOR, null, false));
        Claims claims = claims(token);
        assertEquals(Duration.ofHours(12), lifetime(claims));
        assertEquals(Boolean.FALSE, claims.get("rm", Boolean.class));
    }

    @Test
    void ttlFollowsConfig() {
        JwtService custom = service(30, 2);
        assertEquals(Duration.ofDays(30), lifetime(claims(custom.issue(new CurrentUser(UUID.randomUUID(), Role.PARENT, null, true)))));
        assertEquals(Duration.ofHours(2), lifetime(claims(custom.issue(new CurrentUser(UUID.randomUUID(), Role.PARENT, null, false)))));
    }

    @Test
    void threeArgCurrentUserDefaultsToRememberMe() {
        assertTrue(new CurrentUser(UUID.randomUUID(), Role.PARENT, null).rememberMe());
    }

    @Test
    void verifyRoundTripsModeAndIdentity() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        CurrentUser session = new CurrentUser(userId, Role.DIRECTOR, orgId, false);
        assertEquals(Optional.of(session), jwt.verify(jwt.issue(session)));
        CurrentUser remembered = new CurrentUser(userId, Role.DIRECTOR, orgId, true);
        assertEquals(Optional.of(remembered), jwt.verify(jwt.issue(remembered)));
    }

    /** rm claim 이전에 발급된 토큰(배포 직후 사용자들이 들고 있는 토큰)은 계속 통하고, 로그인 유지로 취급된다. */
    @Test
    void legacyTokenWithoutClaimIsStillValidAndTreatedAsRememberMe() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        String legacy = Jwts.builder()
                .subject(userId.toString())
                .claim("role", Role.PARENT.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofDays(14))))
                .signWith(KEY)
                .compact();
        CurrentUser user = jwt.verify(legacy).orElseThrow();
        assertEquals(userId, user.userId());
        assertEquals(Role.PARENT, user.role());
        assertTrue(user.rememberMe());
    }

    @Test
    void expiredTokenIsRejected() {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", Role.PARENT.name())
                .claim("rm", false)
                .issuedAt(Date.from(past.minus(Duration.ofHours(12))))
                .expiration(Date.from(past))
                .signWith(KEY)
                .compact();
        assertFalse(jwt.verify(expired).isPresent());
    }
}
