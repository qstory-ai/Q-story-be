package com.qstory.backend.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 하루 고정 창 카운터 - Gemini 하루 한도와 같은 시각(미국 태평양 자정)에 0으로 돌아간다. 토큰 버킷은 하루 경계를 넘어
 * 몰아 쓰면 24시간 동안 상한의 두 배까지 허용할 수 있어, 하루 한도를 지키는 데는 이것을 쓴다.
 */
final class DailyQuota {

    static final ZoneId RESET_ZONE = ZoneId.of("America/Los_Angeles");

    private LocalDate day;
    private int used;

    /** 한 번 쓴다. 허용하면 0, 막으면 다음 초기화까지의 초. */
    synchronized long tryAcquire(int limit, Instant now) {
        LocalDate today = now.atZone(RESET_ZONE).toLocalDate();
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        if (used < limit) {
            used++;
            return 0;
        }
        Instant reset = today.plusDays(1).atStartOfDay(RESET_ZONE).toInstant();
        return Math.max(1, Duration.between(now, reset).toSeconds());
    }

    synchronized int used() {
        return used;
    }
}
