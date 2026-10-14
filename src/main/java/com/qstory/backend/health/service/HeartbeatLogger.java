package com.qstory.backend.health.service;

import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 5분마다 "살아 있음" 한 줄(app.heartbeat)을 남긴다. 밤처럼 요청이 없을 때도 로그가 끊기지 않게 해서,
 * Grafana가 "이 줄이 15분째 없음"을 서버가 죽은 신호로 볼 수 있게 한다(ops/grafana-alerting의
 * backend-silent 규칙). 요청 로그(http.request)만으로는 트래픽이 없는 것과 서버가 죽은 것을 구분할 수 없다.
 *
 * <p>같은 줄에 DB 왕복 시간도 남긴다(리전 이전 판단용, docs/dev-log/구조-점검.md 5장). {@code SELECT 1}을
 * {@value #DB_PINGS}번 보내 첫 번째(db_ping_ms - 풀에서 연결을 꺼내는 시간 포함)와 가장 짧은 것(db_ping_min_ms - 순수
 * 네트워크 왕복에 가까움)을 적는다. DB가 응답하지 않으면 -1과 이유를 적고 heartbeat는 그대로 남긴다.
 */
@Component
public class HeartbeatLogger {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatLogger.class);
    static final int DB_PINGS = 3;

    private final JdbcTemplate jdbc;
    private final LongSupplier nanoTime;

    /** 생성자가 둘이라 Spring이 쓸 생성자를 명시한다 - 없으면 기본 생성자를 찾다 부팅이 실패한다. */
    @Autowired
    public HeartbeatLogger(JdbcTemplate jdbc) {
        this(jdbc, System::nanoTime);
    }

    HeartbeatLogger(JdbcTemplate jdbc, LongSupplier nanoTime) {
        this.jdbc = jdbc;
        this.nanoTime = nanoTime;
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void beat() {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        DbPing ping = pingDb();
        log.info(
                "app.heartbeat uptime_s={} heap_used_mb={} heap_max_mb={} db_ping_ms={} db_ping_min_ms={}{}",
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                usedMb,
                runtime.maxMemory() / (1024 * 1024),
                ping.firstMs(),
                ping.minMs(),
                ping.error() == null ? "" : " db_ping_error=" + ping.error());
    }

    DbPing pingDb() {
        long first = -1;
        long min = Long.MAX_VALUE;
        try {
            for (int i = 0; i < DB_PINGS; i++) {
                long startedAt = nanoTime.getAsLong();
                jdbc.queryForObject("select 1", Integer.class);
                long elapsedMs = (nanoTime.getAsLong() - startedAt) / 1_000_000;
                if (i == 0) first = elapsedMs;
                min = Math.min(min, elapsedMs);
            }
            return new DbPing(first, min, null);
        } catch (RuntimeException error) {
            return new DbPing(-1, -1, error.getClass().getSimpleName());
        }
    }

    record DbPing(long firstMs, long minMs, String error) {}
}
