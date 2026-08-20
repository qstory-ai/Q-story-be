package com.qstory.backend.common.util;

import com.qstory.backend.common.error.AbortException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;

/**
 * Java's HttpClient has no cooperative-cancellation signal like Node's AbortController, so each
 * external call instead gets a per-request timeout computed from how much of the shared request
 * budget (qstory.request-timeout-ms) is left. A deadline that has already passed fails fast
 * without even attempting the call, mirroring an AbortController that fired before the next step.
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
