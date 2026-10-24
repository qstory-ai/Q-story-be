package com.qstory.backend.betaevents.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 베타 세션 하나가 10분에 120건을 넘게 보내면 429. 예전에는 요청마다 DB에서 최근 이벤트 수를 셌는데, 이벤트를 대기열로
 * 받으면서(BetaEventIngestQueue) 요청 스레드가 DB를 건드리지 않도록 메모리에서 센다. 서버를 다시 띄우면 기록이 비지만
 * 분석용 이벤트라 괜찮다.
 */
@Component
public class BetaEventRateLimiter {

    static final int MAX_EVENTS = 120;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<UUID, ArrayDeque<Long>> recent = new ConcurrentHashMap<>();

    @Autowired
    public BetaEventRateLimiter() {
        this(Clock.systemUTC());
    }

    BetaEventRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 한도 안이면 이번 이벤트를 세고, 넘으면 429를 던진다(이번 이벤트는 세지 않는다). */
    public void acquire(UUID sessionId) {
        long now = clock.millis();
        long since = now - WINDOW.toMillis();
        boolean[] limited = {false};
        recent.compute(sessionId, (id, times) -> {
            ArrayDeque<Long> window = times == null ? new ArrayDeque<>() : times;
            while (!window.isEmpty() && window.peekFirst() <= since) {
                window.pollFirst();
            }
            if (window.size() >= MAX_EVENTS) {
                limited[0] = true;
            } else {
                window.addLast(now);
            }
            return window;
        });
        if (limited[0]) {
            throw ApiException.contractError(ErrorCode.RATE_LIMITED, "요청이 너무 잦아요.", 429);
        }
    }

    /** 10분 넘게 조용한 세션의 기록을 치워 메모리가 쌓이지 않게 한다. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void evictIdle() {
        long since = clock.millis() - WINDOW.toMillis();
        for (UUID sessionId : recent.keySet()) {
            // compute 계열로 지워야 acquire()와 같은 세션을 동시에 만져도 기록이 엉키지 않는다.
            recent.computeIfPresent(sessionId, (id, window) -> {
                Long last = window.peekLast();
                return last == null || last <= since ? null : window;
            });
        }
    }

    int trackedSessions() {
        return recent.size();
    }
}
