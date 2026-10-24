package com.qstory.backend.discord.service;

import com.qstory.backend.config.DiscordProperties;
import com.qstory.backend.discord.repository.DiscordMetricsRepository;
import com.qstory.backend.discord.repository.DiscordStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 아침 지표 요약(#지표, @운영)과 사용자 반응 30분 묶음(#소식, @운영). 날짜·창은 한국 시간으로 자른다.
 *
 * <p>아침 요약은 (daily-metrics, 날짜)를 discord_alert_log에 먼저 남긴 쪽만 보낸다 - 정기 실행이 겹쳐도 하루 한 번.
 * 묶음은 discord_state의 커서(지난번 창의 끝)부터 지금(30분 단위로 내림)까지를 센다 - 재배포로 한 번 건너뛰어도 다음
 * 실행이 이어서 센다(최대 6시간 전까지). 새로 생긴 것이 0건이면 보내지 않는다.
 */
@Service
public class DiscordReportService {

    private static final Logger log = LoggerFactory.getLogger(DiscordReportService.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Duration WINDOW = Duration.ofMinutes(30);
    static final Duration MAX_CATCH_UP = Duration.ofHours(6);
    static final String DIGEST_CURSOR = "news-digest-cursor";
    static final String DAILY_KEY = "daily-metrics";

    private final DiscordProperties properties;
    private final DiscordNotifier notifier;
    private final DiscordMetricsRepository metrics;
    private final DiscordStateRepository state;
    private final Clock clock;

    @Autowired
    public DiscordReportService(
            DiscordProperties properties, DiscordNotifier notifier, DiscordMetricsRepository metrics,
            DiscordStateRepository state) {
        this(properties, notifier, metrics, state, Clock.systemUTC());
    }

    DiscordReportService(
            DiscordProperties properties, DiscordNotifier notifier, DiscordMetricsRepository metrics,
            DiscordStateRepository state, Clock clock) {
        this.properties = properties;
        this.notifier = notifier;
        this.metrics = metrics;
        this.state = state;
        this.clock = clock;
    }

    public LocalDate yesterday() {
        return LocalDate.now(clock.withZone(KST)).minusDays(1);
    }

    /** 정기 실행 - 어제 요약을 하루 한 번. */
    public void scheduledDailyMetrics() {
        if (!properties.dailyMetricsOn()) {
            return;
        }
        LocalDate day = yesterday();
        if (!state.markOnce(DAILY_KEY, day)) {
            return;
        }
        if (!notifier.send(DiscordChannel.METRICS, dailyMetricsText(day), List.of(DiscordMention.OPS))) {
            state.unmark(DAILY_KEY, day);
        }
    }

    /** 수동 실행 - 중복 방지 없이 지금 보내고 결과를 돌려준다. */
    public boolean sendDailyMetricsNow(LocalDate day) {
        return notifier.sendNow(DiscordChannel.METRICS, dailyMetricsText(day), List.of(DiscordMention.OPS));
    }

    String dailyMetricsText(LocalDate day) {
        return DiscordMessages.dailyMetrics(dayMetrics(day), dayMetrics(day.minusDays(1)), properties.metricsLink());
    }

    private DiscordMessages.Day dayMetrics(LocalDate day) {
        Instant from = day.atStartOfDay(KST).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(KST).toInstant();
        DiscordMetricsRepository.GaTraffic ga;
        try {
            ga = metrics.gaTraffic(day);
        } catch (RuntimeException gaMissing) {
            // GA 테이블·뷰가 없거나 읽지 못하면 GA 줄만 뺀다.
            log.warn("discord.daily-metrics ga-unavailable reason={}", gaMissing.getClass().getSimpleName());
            ga = null;
        }
        return new DiscordMessages.Day(day, ga, metrics.funnel(from, to), metrics.signupsByRole(from, to),
                metrics.launchNotifications(from, to), metrics.surveys(from, to));
    }

    /** 30분 묶음 - 정기 실행. 보냈으면 true. */
    public boolean runNewsDigest() {
        if (!properties.newsDigestOn()) {
            return false;
        }
        Instant end = floorToWindow(clock.instant());
        Instant start = end.minus(WINDOW);
        Optional<String> cursor = state.state(DIGEST_CURSOR);
        if (cursor.isPresent()) {
            try {
                Instant last = Instant.parse(cursor.get());
                if (!last.isBefore(end)) {
                    return false; // 이 창은 이미 셌다.
                }
                start = last.isBefore(end.minus(MAX_CATCH_UP)) ? end.minus(MAX_CATCH_UP) : last;
            } catch (RuntimeException unreadable) {
                // 커서를 못 읽으면 직전 창 하나만.
            }
        }
        DiscordMessages.Window window = new DiscordMessages.Window(
                start.atZone(KST).toLocalTime(), end.atZone(KST).toLocalTime(),
                metrics.surveys(start, end), metrics.launchNotifications(start, end), metrics.organizations(start, end),
                metrics.joinRequests(start, end), metrics.signupsByRole(start, end), metrics.feedback(start, end),
                metrics.partnerships(start, end));
        state.putState(DIGEST_CURSOR, end.toString());
        if (window.empty()) {
            return false;
        }
        return notifier.send(DiscordChannel.NEWS, DiscordMessages.newsDigest(window, properties.newsLink()),
                List.of(DiscordMention.OPS));
    }

    static Instant floorToWindow(Instant now) {
        ZonedDateTime kst = now.atZone(KST).withSecond(0).withNano(0);
        return kst.withMinute(kst.getMinute() < 30 ? 0 : 30).toInstant();
    }
}
