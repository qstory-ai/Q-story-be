package com.qstory.backend.betaevents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qstory.backend.common.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 베타 세션당 10분에 120건 - 넘으면 429. 요청 스레드에서 DB 없이 센다. */
class BetaEventRateLimiterTest {

    private Instant now = Instant.parse("2026-10-24T12:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final BetaEventRateLimiter limiter = new BetaEventRateLimiter(clock);
    private final UUID session = UUID.randomUUID();

    @Test
    void rejectsThe121stEventInTheWindow() {
        for (int i = 0; i < BetaEventRateLimiter.MAX_EVENTS; i++) {
            limiter.acquire(session);
        }

        assertThatThrownBy(() -> limiter.acquire(session))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).statusCode()).isEqualTo(429));
        // 다른 세션은 영향을 받지 않는다.
        limiter.acquire(UUID.randomUUID());
    }

    @Test
    void allowsAgainAfterTheWindowSlides() {
        for (int i = 0; i < BetaEventRateLimiter.MAX_EVENTS; i++) {
            limiter.acquire(session);
        }
        now = now.plus(BetaEventRateLimiter.WINDOW).plusMillis(1);

        limiter.acquire(session);
    }

    @Test
    void evictsIdleSessions() {
        limiter.acquire(session);
        now = now.plus(Duration.ofMinutes(5));
        UUID active = UUID.randomUUID();
        limiter.acquire(active);
        now = now.plus(Duration.ofMinutes(6));

        limiter.evictIdle();

        assertThat(limiter.trackedSessions()).isEqualTo(1);
    }
}
