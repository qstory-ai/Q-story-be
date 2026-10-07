package com.qstory.backend.health.service;

import java.lang.management.ManagementFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 5분마다 "살아 있음" 한 줄(app.heartbeat)을 남긴다. 밤처럼 요청이 없을 때도 로그가 끊기지 않게 해서,
 * Grafana가 "이 줄이 15분째 없음"을 서버가 죽은 신호로 볼 수 있게 한다(ops/grafana-alerting의
 * backend-silent 규칙). 요청 로그(http.request)만으로는 트래픽이 없는 것과 서버가 죽은 것을 구분할 수 없다.
 */
@Component
public class HeartbeatLogger {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatLogger.class);

    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void beat() {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        log.info(
                "app.heartbeat uptime_s={} heap_used_mb={} heap_max_mb={}",
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                usedMb,
                runtime.maxMemory() / (1024 * 1024));
    }
}
