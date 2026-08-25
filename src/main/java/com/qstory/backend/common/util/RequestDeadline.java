package com.qstory.backend.common.util;

import com.qstory.backend.common.error.AbortException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;

/**
 * Java의 HttpClient에는 Node의 AbortController 같은 협조적 취소(cooperative-cancellation)
 * 신호가 없다. 그래서 각 외부 호출은 대신, 공유된 요청 예산(qstory.request-timeout-ms) 중
 * 남은 시간으로부터 계산한 요청별 타임아웃을 받는다. 이미 지나버린 데드라인은 호출 시도조차
 * 하지 않고 즉시 실패하며, 이는 다음 단계 전에 이미 발동해버린 AbortController와 동일하게
 * 동작하는 것이다.
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
