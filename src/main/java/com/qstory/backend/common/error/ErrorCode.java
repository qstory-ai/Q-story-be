package com.qstory.backend.common.error;

/**
 * Every code thrown as an {@link ApiException} - request-shape violations caught before a
 * pipeline runs (validation, size limits, CORS, unknown routes). Consolidated from server.mjs,
 * question-contract.mjs, narration-contract.mjs and story-registry.mjs's contractError() call
 * sites. defaultStatus is the HTTP status every one of those call sites actually used.
 */
public enum ErrorCode {
    MISSING_REQUEST_CONTEXT(400),
    INVALID_REQUEST_CONTEXT(400),
    UNSUPPORTED_AUDIO_TYPE(415),
    INVALID_TEXT_QUESTION(400),
    ORIGIN_NOT_ALLOWED(403),
    AUDIO_TOO_LARGE(413),
    EMPTY_AUDIO(400),
    UNSUPPORTED_CONTENT_TYPE(415),
    NARRATION_REQUEST_TOO_LARGE(413),
    INVALID_JSON(400),
    INVALID_BASE64_AUDIO_UPLOAD(400),
    STORY_NOT_REGISTERED(404),
    STORY_NOT_AVAILABLE(403),
    STORY_CONTEXT_NOT_ALLOWED(400),
    NARRATION_STORY_NOT_ALLOWED(403),
    NARRATION_SPEAKER_NOT_ALLOWED(403),
    NARRATION_VOICE_NOT_ALLOWED(403),
    INVALID_NARRATION_REQUEST(400),
    INVALID_NARRATION_TEXT(400),
    NOT_FOUND(404),
    INTERNAL_ERROR(500);

    private final int defaultStatus;

    ErrorCode(int defaultStatus) {
        this.defaultStatus = defaultStatus;
    }

    public int defaultStatus() {
        return defaultStatus;
    }
}
