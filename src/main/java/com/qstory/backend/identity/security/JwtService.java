package com.qstory.backend.identity.security;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 이 앱 자체의 액세스 토큰을 발급하고 검증한다 - HMAC 서명 방식의 단일 토큰이다. 수명은 "로그인 유지" 여부(rm claim)로
 * 정해지고(remember-me-ttl-days / session-ttl-hours), 클라이언트는 만료 전에 POST /v1/auth/refresh로 같은 모드의 새
 * 토큰을 받는다. rm claim이 없는 예전 토큰은 로그인 유지 토큰으로 취급한다.
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_ORG_ID = "orgId";
    /** "로그인 유지" 여부. 없으면(이 claim 이전에 발급된 토큰) true로 본다. */
    static final String CLAIM_REMEMBER_ME = "rm";

    private final AppProperties config;

    /**
     * 요청마다 다시 만들지 않도록 처음 쓸 때 한 번 만들어 둔다. 부팅 시점에 만들지 않는 이유: secret이
     * 32바이트 미만이면 Keys.hmacShaKeyFor가 던지는데, 그때도 앱은 떠 있고 인증만 실패해야 한다.
     */
    private volatile SecretKey key;
    private volatile JwtParser parser;

    public JwtService(AppProperties config) {
        this.config = config;
    }

    /**
     * 앱 부팅 시점에 JWT secret 유무를 로그에 알린다 - secret이 비어 있으면 verify()가 조용히
     * Optional.empty()를 반환해 모든 요청을 익명으로 처리하고 issue()는 500으로 실패한다.
     * 결과적으로 "떴지만 아무도 로그인할 수 없는" 상태로 방치될 수 있어서, 운영자가 이 WARN
     * 라인을 부팅 직후에 보고 즉시 알아채도록 한다.
     */
    @PostConstruct
    void warnIfNotConfigured() {
        if (!config.auth().configured()) {
            log.warn("jwt-service.secret-missing — 인증 기능 사용 불가. qstory.auth.jwt-secret 환경 변수를 설정하세요.");
        }
    }

    /** user.rememberMe()에 따라 수명을 정해 발급한다. */
    public String issue(CurrentUser user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.userId().toString())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_REMEMBER_ME, user.rememberMe())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl(user.rememberMe()))));
        if (user.orgId() != null) {
            builder.claim(CLAIM_ORG_ID, user.orgId().toString());
        }
        return builder.signWith(key()).compact();
    }

    /** 토큰이 없거나 형식이 잘못되었거나 만료된 경우 Optional.empty()를 반환 - 대부분의 호출자가 익명 요청을 허용해야 하므로 절대 예외를 던지지 않는다. */
    public Optional<CurrentUser> verify(String token) {
        if (!config.auth().configured()) {
            return Optional.empty();
        }
        try {
            Claims claims = parser().parseSignedClaims(token).getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            UUID orgId = uuidOrNull(claims.get(CLAIM_ORG_ID, String.class));
            Boolean rememberMe = claims.get(CLAIM_REMEMBER_ME, Boolean.class);
            return Optional.of(new CurrentUser(userId, role, orgId, rememberMe == null || rememberMe));
        } catch (JwtException | IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    Duration ttl(boolean rememberMe) {
        return rememberMe
                ? Duration.ofDays(config.auth().rememberMeTtlDays())
                : Duration.ofHours(config.auth().sessionTtlHours());
    }

    private SecretKey key() {
        if (!config.auth().configured()) {
            throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "인증 기능이 아직 준비되지 않았어요.", 500);
        }
        SecretKey current = key;
        if (current == null) {
            current = Keys.hmacShaKeyFor(config.auth().jwtSecret().getBytes(StandardCharsets.UTF_8));
            key = current;
        }
        return current;
    }

    private JwtParser parser() {
        JwtParser current = parser;
        if (current == null) {
            current = Jwts.parser().verifyWith(key()).build();
            parser = current;
        }
        return current;
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
