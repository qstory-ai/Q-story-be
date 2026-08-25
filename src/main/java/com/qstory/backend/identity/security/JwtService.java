package com.qstory.backend.identity.security;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** 이 앱 자체의 액세스 토큰을 발급하고 검증한다 - HMAC 서명 방식의 단일한 장기 유효 토큰이며, 리프레시(refresh) 흐름은 없다(auth plan 문서 참고). */
@Component
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_ORG_ID = "orgId";
    private static final String CLAIM_CLASS_ID = "classId";

    private final AppProperties config;

    public JwtService(AppProperties config) {
        this.config = config;
    }

    public String issue(CurrentUser user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.userId().toString())
                .claim(CLAIM_ROLE, user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(config.auth().accessTokenTtlMinutes()))));
        if (user.orgId() != null) {
            builder.claim(CLAIM_ORG_ID, user.orgId().toString());
        }
        if (user.classId() != null) {
            builder.claim(CLAIM_CLASS_ID, user.classId().toString());
        }
        return builder.signWith(key()).compact();
    }

    /** 토큰이 없거나 형식이 잘못되었거나 만료된 경우 Optional.empty()를 반환 - 대부분의 호출자가 익명 요청을 허용해야 하므로 절대 예외를 던지지 않는다. */
    public Optional<CurrentUser> verify(String token) {
        if (!config.auth().configured()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            UUID orgId = uuidOrNull(claims.get(CLAIM_ORG_ID, String.class));
            UUID classId = uuidOrNull(claims.get(CLAIM_CLASS_ID, String.class));
            return Optional.of(new CurrentUser(userId, role, orgId, classId));
        } catch (JwtException | IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private SecretKey key() {
        if (!config.auth().configured()) {
            throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "인증 기능이 아직 준비되지 않았어요.", 500);
        }
        return Keys.hmacShaKeyFor(config.auth().jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
