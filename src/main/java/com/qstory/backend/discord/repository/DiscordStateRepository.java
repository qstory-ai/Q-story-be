package com.qstory.backend.discord.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** discord_alert_log·discord_state·provider_daily_usage(db/schema/086). */
@Repository
public class DiscordStateRepository {

    static final String MARK_ONCE = """
            insert into public.discord_alert_log (alert_key, alert_day, sent_at) values (:key, :day, now())
            on conflict (alert_key, alert_day) do nothing
            """;
    static final String UNMARK = "delete from public.discord_alert_log where alert_key = :key and alert_day = :day";
    static final String GET_STATE = "select state_value from public.discord_state where state_key = :key";
    static final String PUT_STATE = """
            insert into public.discord_state (state_key, state_value, updated_at) values (:key, :value, now())
            on conflict (state_key) do update set state_value = excluded.state_value, updated_at = now()
            """;
    static final String INCREMENT_USAGE = """
            insert into public.provider_daily_usage (provider, usage_day, calls, updated_at) values (:provider, :day, 1, now())
            on conflict (provider, usage_day) do update set calls = public.provider_daily_usage.calls + 1, updated_at = now()
            returning calls
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DiscordStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 이 (키, 날짜)를 처음 남겼으면 true - 그때만 보낸다. 두 번째부터는 false. */
    public boolean markOnce(String key, LocalDate day) {
        return jdbc.update(MARK_ONCE, keyDay(key, day)) == 1;
    }

    /** 보내기를 넘기지 못했을 때 되돌려 다음 확인 때 다시 보내게 한다. */
    public void unmark(String key, LocalDate day) {
        jdbc.update(UNMARK, keyDay(key, day));
    }

    public Optional<String> state(String key) {
        List<String> rows = jdbc.queryForList(GET_STATE, new MapSqlParameterSource("key", key), String.class);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public void putState(String key, String value) {
        jdbc.update(PUT_STATE, new MapSqlParameterSource().addValue("key", key).addValue("value", value));
    }

    /** 오늘 호출 수를 하나 늘리고 늘린 뒤의 수를 돌려준다. */
    public int incrementUsage(String provider, LocalDate day) {
        Integer calls = jdbc.queryForObject(INCREMENT_USAGE,
                new MapSqlParameterSource().addValue("provider", provider).addValue("day", Date.valueOf(day)),
                Integer.class);
        return calls == null ? 0 : calls;
    }

    private static MapSqlParameterSource keyDay(String key, LocalDate day) {
        return new MapSqlParameterSource().addValue("key", key).addValue("day", Date.valueOf(day));
    }
}
