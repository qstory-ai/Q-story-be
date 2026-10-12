package com.qstory.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 토큰 버킷 계산 - 처음엔 capacity번 몰아 쓰고, 그 뒤로는 window/capacity마다 한 번씩 다시 찬다. */
class TokenBucketLimiterTest {

    private static final long MINUTE = Duration.ofMinutes(1).toNanos();
    private static final long SECOND = Duration.ofSeconds(1).toNanos();

    @Test
    void allowsCapacityThenBlocksWithRetryAfter() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(100);
        long t = 1_000 * SECOND;
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("k", 10, 10 * MINUTE, t)).isZero();
        }
        // 10분에 10번 = 1분에 1개씩 찬다 - 비었으니 60초 뒤.
        assertThat(limiter.tryAcquire("k", 10, 10 * MINUTE, t)).isEqualTo(60);
        assertThat(limiter.tryAcquire("k", 10, 10 * MINUTE, t + 30 * SECOND)).isEqualTo(30);
        assertThat(limiter.tryAcquire("k", 10, 10 * MINUTE, t + 60 * SECOND)).isZero();
        assertThat(limiter.tryAcquire("k", 10, 10 * MINUTE, t + 60 * SECOND)).isPositive();
    }

    @Test
    void refillsUpToCapacityOnlyAfterRest() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(100);
        long t = 0;
        for (int i = 0; i < 5; i++) limiter.tryAcquire("k", 5, MINUTE, t);
        // 한참 쉬어도 capacity(5)를 넘게 쌓이지 않는다.
        long later = t + 60 * MINUTE;
        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire("k", 5, MINUTE, later)).isZero();
        assertThat(limiter.tryAcquire("k", 5, MINUTE, later)).isPositive();
    }

    @Test
    void keysAreIndependent() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(100);
        assertThat(limiter.tryAcquire("a", 1, MINUTE, 0)).isZero();
        assertThat(limiter.tryAcquire("a", 1, MINUTE, 0)).isPositive();
        assertThat(limiter.tryAcquire("b", 1, MINUTE, 0)).isZero();
    }

    @Test
    void peekDoesNotConsume() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(100);
        assertThat(limiter.peek("k", 1, MINUTE, 0)).isZero();
        assertThat(limiter.tryAcquire("k", 1, MINUTE, 0)).isZero();
        assertThat(limiter.peek("k", 1, MINUTE, 0)).isEqualTo(60);
        assertThat(limiter.peek("k", 1, MINUTE, 0)).isEqualTo(60);
    }

    @Test
    void evictsRestedBucketsWhenFull() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(2);
        limiter.tryAcquire("a", 5, MINUTE, 0);
        limiter.tryAcquire("b", 5, MINUTE, 0);
        assertThat(limiter.size()).isEqualTo(2);
        // 1분 뒤엔 a·b 모두 다시 가득 찼다 - 새 키를 위해 지운다.
        assertThat(limiter.tryAcquire("c", 5, MINUTE, MINUTE)).isZero();
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void whenTableIsFullOfActiveBucketsNewKeysAreAllowedUntracked() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1);
        limiter.tryAcquire("a", 5, MINUTE, 0);
        assertThat(limiter.tryAcquire("b", 5, MINUTE, 0)).isZero();
        assertThat(limiter.size()).isEqualTo(1);
    }
}
