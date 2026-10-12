package com.qstory.backend.ratelimit;

/**
 * 상한에 걸렸다 - GlobalExceptionHandler가 429 + Retry-After + {ok:false, failure:{code:RATE_LIMITED}}로 답한다.
 * 필터에서 걸린 요청은 RateLimitFilter가 같은 모양으로 직접 쓴다.
 */
public class RateLimitedException extends RuntimeException {

    private final RateLimitRule rule;
    private final long retryAfterSeconds;

    public RateLimitedException(RateLimitRule rule, long retryAfterSeconds) {
        super(RateLimitService.safeDetail(rule, retryAfterSeconds));
        this.rule = rule;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public RateLimitRule rule() {
        return rule;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String safeDetail() {
        return getMessage();
    }
}
