package com.qstory.backend.discord.repository;

import java.sql.Date;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 디스코드 알림에 넣을 숫자만 센다 - 이름·연락처·아이 정보·후기 본문은 읽지 않는다. 퍼널 이벤트는 story_sessions.traffic_type이
 * DEV·QA인 세션을 뺀다(Grafana 대시보드와 같은 기준). GA 숫자는 grafana.ga_daily_traffic(db/schema/085)이 있을 때만.
 */
@Repository
public class DiscordMetricsRepository {

    /** 가입으로 세는 역할 - 반 계정(원장이 만든다)·STAFF(내부)는 뺀다. */
    static final String SIGNUPS_BY_ROLE = """
            select role, count(*) as n from public.app_user
            where created_at >= :from and created_at < :to and role in ('PARENT', 'TUTOR', 'DIRECTOR')
            group by role
            """;

    static final String FUNNEL = """
            select
                count(distinct e.session_id) filter (where e.event_name = 'STORY_STARTED') as starts,
                count(distinct e.session_id) filter (where e.event_name = 'STORY_COMPLETED') as completions,
                count(distinct e.session_id) filter (where e.event_name in ('PARENT_REPORT_OPENED', 'REPORT_VIEWED')) as reports
            from public.story_events e
            join public.story_sessions s on s.id = e.session_id
            where e.occurred_at >= :from and e.occurred_at < :to
              and e.event_name in ('STORY_STARTED', 'STORY_COMPLETED', 'PARENT_REPORT_OPENED', 'REPORT_VIEWED')
              and s.traffic_type not in ('DEV', 'QA')
            """;

    static final String LAUNCH_NOTIFICATIONS =
            "select count(*) from public.launch_notification_requests where created_at >= :from and created_at < :to";

    /** 마이페이지 기능 제안·오류 제보(앱이 message 앞에 [기능 제안]/[오류 제보]를 붙인다). 30분 묶음에 미리보기로 넣는다. */
    static final String FEEDBACK = """
            select f.message, u.role from public.improvement_feedback f
            left join public.app_user u on u.id = f.user_id
            where f.created_at >= :from and f.created_at < :to
            order by f.created_at
            """;

    /** 랜딩 협업 문의 - 소속·역할·내용만(이름·이메일·전화는 고르지 않는다). */
    static final String PARTNERSHIPS = """
            select affiliation, role, message from public.partnership_inquiries
            where created_at >= :from and created_at < :to
            order by created_at
            """;

    static final String SURVEYS = """
            select count(*) as n, avg(child_engagement) as engagement, avg(report_helpfulness) as helpfulness
            from public.completion_surveys where created_at >= :from and created_at < :to
            """;

    static final String ORGANIZATIONS =
            "select count(*) from public.organization where created_at >= :from and created_at < :to";

    static final String JOIN_REQUESTS = """
            select
                count(*) filter (where created_at >= :from and created_at < :to) as created,
                count(*) filter (where status = 'PENDING') as pending
            from public.organization_join_request
            where status = 'PENDING' or (created_at >= :from and created_at < :to)
            """;

    static final String GA_AVAILABLE = "select to_regclass('grafana.ga_daily_traffic') is not null";
    static final String GA_HAS_DAY = "select exists (select 1 from grafana.ga_daily_traffic where metric_date = :day)";
    static final String GA_TRAFFIC = """
            select coalesce(sum(total_users), 0) as visitors,
                   coalesce(sum(sessions), 0) as sessions,
                   coalesce(sum(total_users) filter (where channel = 'QR'), 0) as qr_visitors,
                   coalesce(sum(sessions) filter (where channel = 'QR'), 0) as qr_sessions
            from grafana.ga_daily_traffic
            where metric_date = :day and traffic_type not in ('DEV', 'QA')
            """;

    public record Funnel(long starts, long completions, long reports) {}

    /** 마이페이지 요청 한 건 - 남긴 글과 남긴 사람 역할(없으면 null). */
    public record Feedback(String message, String role) {}

    /** 협업 문의 한 건 - 소속, 역할, 내용. */
    public record Partnership(String affiliation, String role, String message) {}

    public record Surveys(long count, Double engagement, Double helpfulness) {}

    public record JoinRequests(long created, long pending) {}

    public record GaTraffic(long visitors, long sessions, long qrVisitors, long qrSessions) {}

    private final NamedParameterJdbcTemplate jdbc;

    public DiscordMetricsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 역할 → 가입 수(PARENT·TUTOR·DIRECTOR 순서, 0도 넣는다). */
    public Map<String, Long> signupsByRole(Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PARENT", 0L);
        counts.put("TUTOR", 0L);
        counts.put("DIRECTOR", 0L);
        jdbc.query(SIGNUPS_BY_ROLE, range(from, to),
                rs -> { counts.put(rs.getString("role"), rs.getLong("n")); });
        return counts;
    }

    public Funnel funnel(Instant from, Instant to) {
        return jdbc.queryForObject(FUNNEL, range(from, to),
                (rs, i) -> new Funnel(rs.getLong("starts"), rs.getLong("completions"), rs.getLong("reports")));
    }

    public long launchNotifications(Instant from, Instant to) {
        return count(LAUNCH_NOTIFICATIONS, from, to);
    }

    public List<Feedback> feedback(Instant from, Instant to) {
        return jdbc.query(FEEDBACK, range(from, to),
                (rs, i) -> new Feedback(rs.getString("message"), rs.getString("role")));
    }

    public List<Partnership> partnerships(Instant from, Instant to) {
        return jdbc.query(PARTNERSHIPS, range(from, to), (rs, i) ->
                new Partnership(rs.getString("affiliation"), rs.getString("role"), rs.getString("message")));
    }

    public Surveys surveys(Instant from, Instant to) {
        return jdbc.queryForObject(SURVEYS, range(from, to), (rs, i) -> new Surveys(
                rs.getLong("n"), nullableDouble(rs.getObject("engagement")), nullableDouble(rs.getObject("helpfulness"))));
    }

    public long organizations(Instant from, Instant to) {
        return count(ORGANIZATIONS, from, to);
    }

    public JoinRequests joinRequests(Instant from, Instant to) {
        return jdbc.queryForObject(JOIN_REQUESTS, range(from, to),
                (rs, i) -> new JoinRequests(rs.getLong("created"), rs.getLong("pending")));
    }

    /** GA 테이블이 없거나 그날 받은 행이 아직 없으면 null. */
    public GaTraffic gaTraffic(LocalDate day) {
        Boolean available = jdbc.queryForObject(GA_AVAILABLE, new MapSqlParameterSource(), Boolean.class);
        if (!Boolean.TRUE.equals(available)) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("day", Date.valueOf(day));
        Boolean hasDay = jdbc.queryForObject(GA_HAS_DAY, params, Boolean.class);
        if (!Boolean.TRUE.equals(hasDay)) {
            return null;
        }
        return jdbc.queryForObject(GA_TRAFFIC, params, (rs, i) -> new GaTraffic(
                rs.getLong("visitors"), rs.getLong("sessions"), rs.getLong("qr_visitors"), rs.getLong("qr_sessions")));
    }

    private long count(String sql, Instant from, Instant to) {
        Long n = jdbc.queryForObject(sql, range(from, to), Long.class);
        return n == null ? 0 : n;
    }

    private static Double nullableDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static MapSqlParameterSource range(Instant from, Instant to) {
        return new MapSqlParameterSource()
                .addValue("from", from.atOffset(ZoneOffset.UTC))
                .addValue("to", to.atOffset(ZoneOffset.UTC));
    }
}
