package com.qstory.backend.identity.repository;

import com.qstory.backend.identity.dto.AuthResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 로그인 기록(auth_login_log, db/schema/095) - 그라파나 "로그인한 사람 수"용. 로그인 유지(REFRESH)는 하루 한 사람 한 줄.
 * 기록에 실패해도 로그인은 그대로 성공시킨다(통계용이라 경고 로그만 남긴다).
 */
@Repository
public class LoginLogRepository {

    private static final Logger log = LoggerFactory.getLogger(LoginLogRepository.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static final String INSERT = """
            insert into public.auth_login_log (user_id, role, method, login_day) values (:userId, :role, :method, :day)
            on conflict (user_id, login_day) where method = 'REFRESH' do nothing
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public LoginLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** method: PASSWORD / GOOGLE / KAKAO / REFRESH. */
    public void record(AuthResponse response, String method) {
        if (response == null || response.user() == null || response.user().id() == null) {
            return;
        }
        try {
            jdbc.update(INSERT, new MapSqlParameterSource()
                    .addValue("userId", response.user().id())
                    .addValue("role", response.user().role())
                    .addValue("method", method)
                    .addValue("day", LocalDate.now(SEOUL)));
        } catch (RuntimeException e) {
            log.warn("event=login_log_failed method={} error={}", method, e.getClass().getSimpleName());
        }
    }
}
