package com.qstory.backend.analytics.repository;

import com.qstory.backend.analytics.service.GaRows;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * ga_daily_metrics·ga_daily_traffic·ga_import_run(db/schema/085). 가져온 기간은 날짜 단위로 갈아 끼운다 - 그 기간의
 * 행을 지우고 받은 행을 넣는다(같은 키가 두 번 오면 나중 값으로 덮는다). GA에서 사라진 분할이 남지 않게 하려는 것.
 * 호출하는 쪽(GaImportService)이 한 트랜잭션으로 묶는다.
 */
@Repository
public class GaMetricsRepository {

    static final String DELETE_EVENTS =
            "delete from public.ga_daily_metrics where metric_date between :start and :end";
    static final String DELETE_TRAFFIC =
            "delete from public.ga_daily_traffic where metric_date between :start and :end";

    static final String UPSERT_EVENT = """
            insert into public.ga_daily_metrics (metric_date, host_name, session_source, session_medium, session_campaign,
                qs_platform, qs_surface, event_name, event_count, total_users, sessions, imported_at)
            values (:date, :host, :source, :medium, :campaign, :platform, :surface, :eventName,
                :eventCount, :totalUsers, :sessions, :importedAt)
            on conflict (metric_date, host_name, session_source, session_medium, session_campaign, qs_platform, qs_surface, event_name)
            do update set event_count = excluded.event_count, total_users = excluded.total_users,
                sessions = excluded.sessions, imported_at = excluded.imported_at
            """;

    static final String UPSERT_TRAFFIC = """
            insert into public.ga_daily_traffic (metric_date, host_name, session_source, session_medium, session_campaign,
                qs_platform, qs_surface, total_users, new_users, sessions, engaged_sessions, page_views, imported_at)
            values (:date, :host, :source, :medium, :campaign, :platform, :surface,
                :totalUsers, :newUsers, :sessions, :engagedSessions, :pageViews, :importedAt)
            on conflict (metric_date, host_name, session_source, session_medium, session_campaign, qs_platform, qs_surface)
            do update set total_users = excluded.total_users, new_users = excluded.new_users, sessions = excluded.sessions,
                engaged_sessions = excluded.engaged_sessions, page_views = excluded.page_views, imported_at = excluded.imported_at
            """;

    static final String INSERT_RUN = """
            insert into public.ga_import_run (started_at, finished_at, start_date, end_date, status, event_rows, traffic_rows, error)
            values (:startedAt, :finishedAt, :start, :end, :status, :eventRows, :trafficRows, :error)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public GaMetricsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEmpty() {
        Boolean exists = jdbc.getJdbcTemplate()
                .queryForObject("select exists (select 1 from public.ga_daily_traffic)", Boolean.class);
        return !Boolean.TRUE.equals(exists);
    }

    public void replaceEvents(LocalDate start, LocalDate end, List<GaRows.EventRow> rows, Instant importedAt) {
        jdbc.update(DELETE_EVENTS, range(start, end));
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = rows.stream()
                .map(row -> key(row.key(), importedAt)
                        .addValue("eventName", row.eventName())
                        .addValue("eventCount", row.eventCount())
                        .addValue("totalUsers", row.totalUsers())
                        .addValue("sessions", row.sessions()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_EVENT, batch);
    }

    public void replaceTraffic(LocalDate start, LocalDate end, List<GaRows.TrafficRow> rows, Instant importedAt) {
        jdbc.update(DELETE_TRAFFIC, range(start, end));
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = rows.stream()
                .map(row -> key(row.key(), importedAt)
                        .addValue("totalUsers", row.totalUsers())
                        .addValue("newUsers", row.newUsers())
                        .addValue("sessions", row.sessions())
                        .addValue("engagedSessions", row.engagedSessions())
                        .addValue("pageViews", row.pageViews()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_TRAFFIC, batch);
    }

    public void recordRun(
            Instant startedAt, Instant finishedAt, LocalDate start, LocalDate end, boolean success,
            int eventRows, int trafficRows, String error) {
        jdbc.update(INSERT_RUN, range(start, end)
                .addValue("startedAt", Timestamp.from(startedAt))
                .addValue("finishedAt", Timestamp.from(finishedAt))
                .addValue("status", success ? "SUCCESS" : "FAILED")
                .addValue("eventRows", eventRows)
                .addValue("trafficRows", trafficRows)
                .addValue("error", error == null ? null : error.length() > 500 ? error.substring(0, 500) : error));
    }

    private static MapSqlParameterSource range(LocalDate start, LocalDate end) {
        return new MapSqlParameterSource()
                .addValue("start", Date.valueOf(start))
                .addValue("end", Date.valueOf(end));
    }

    private static MapSqlParameterSource key(GaRows.Key key, Instant importedAt) {
        return new MapSqlParameterSource()
                .addValue("date", Date.valueOf(key.date()))
                .addValue("host", key.hostName())
                .addValue("source", key.source())
                .addValue("medium", key.medium())
                .addValue("campaign", key.campaign())
                .addValue("platform", key.platform())
                .addValue("surface", key.surface())
                .addValue("importedAt", Timestamp.from(importedAt));
    }
}
