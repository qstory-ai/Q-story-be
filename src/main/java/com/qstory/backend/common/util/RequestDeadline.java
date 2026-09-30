package com.qstory.backend.common.util;

import com.qstory.backend.common.error.AbortException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;

/**
 * 요청 하나의 공유 시간 예산(qstory.request-timeout-ms). Java HttpClient에는 협조적 취소 신호가
 * 없으므로, 각 외부 호출은 남은 시간으로 계산한 요청별 타임아웃을 받는다. 이미 지난 데드라인은
 * 호출을 시도하지 않고 즉시 AbortException으로 실패한다.
 */
public final class RequestDeadline {

    private final Instant deadline;

    private RequestDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public static RequestDeadline startingNow(long timeoutMs) {
        return new RequestDeadline(Instant.now().plusMillis(timeoutMs));
    }

    public Duration remaining() {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public void requireTimeRemaining() {
        if (remaining().isZero()) {
            throw new AbortException("request-timeout");
        }
    }

    public HttpRequest.Builder applyTo(HttpRequest.Builder builder) {
        requireTimeRemaining();
        return builder.timeout(remaining());
    }
}
