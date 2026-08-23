package com.qstory.backend.common.error;

/**
 * Error codes for the beta-events/voice-research endpoints - ported from the Supabase Edge
 * Functions (beta-events/index.ts, voice-research/index.ts), which used a distinct
 * {@code {ok:false, error:'snake_case'}} response shape and its own snake_case code namespace,
 * independent of the child-facing {@link ErrorCode}/{@link ProviderErrorCode} envelopes.
 */
public enum EdgeErrorCode {
    ORIGIN_NOT_ALLOWED(403),
    METHOD_NOT_ALLOWED(405),
    PAYLOAD_TOO_LARGE(413),
    INVALID_JSON(400),
    INVALID_PAYLOAD(400),
    UNSUPPORTED_FIELD(400),
    VALIDATION_FAILED(400),
    SERVER_NOT_CONFIGURED(500),
    STORAGE_FAILED(500),
    RATE_LIMITED(429),
    INVALID_FORM_DATA(400),
    INVALID_CONSENT_TIME(400),
    CONSENT_INVALID(403),
    UNSUPPORTED_ACTION(400),
    CLEANUP_UNAUTHORIZED(401),
    ADMIN_UNAUTHORIZED(401),
    STORY_NOT_FOUND(404);

    private final int defaultStatus;

    EdgeErrorCode(int defaultStatus) {
        this.defaultStatus = defaultStatus;
    }

    public int defaultStatus() {
        return defaultStatus;
    }

    /** The wire code is snake_case (e.g. "origin_not_allowed"), matching the original edge functions verbatim. */
    public String wireCode() {
        return name().toLowerCase();
    }
}
