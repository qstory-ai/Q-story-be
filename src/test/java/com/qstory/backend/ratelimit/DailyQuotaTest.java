package com.qstory.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Gemini 하루 한도와 같은 시각(미국 태평양 자정)에 다시 센다. */
class DailyQuotaTest {

    @Test
    void countsUpToTheLimitWithinOnePacificDay() {
        DailyQuota quota = new DailyQuota();
        Instant morning = Instant.parse("2026-10-12T15:00:00Z"); // 08:00 PDT
        assertThat(quota.tryAcquire(2, morning)).isZero();
        assertThat(quota.tryAcquire(2, morning.plusSeconds(60))).isZero();
        // 다음 초기화는 2026-10-13 00:00 PDT = 07:00 UTC - 16시간 남음.
        assertThat(quota.tryAcquire(2, morning)).isEqualTo(16 * 3600);
    }

    @Test
    void resetsAtPacificMidnightNotUtcMidnight() {
        DailyQuota quota = new DailyQuota();
        assertThat(quota.tryAcquire(1, Instant.parse("2026-10-12T23:30:00Z"))).isZero(); // 16:30 PDT
        // UTC로는 날이 바뀌었지만 태평양 시간은 아직 같은 날(17:30 PDT).
        assertThat(quota.tryAcquire(1, Instant.parse("2026-10-13T00:30:00Z"))).isPositive();
        // 00:00 PDT가 지나면 다시 센다.
        assertThat(quota.tryAcquire(1, Instant.parse("2026-10-13T07:00:01Z"))).isZero();
    }
}
