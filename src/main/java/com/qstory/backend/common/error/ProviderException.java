package com.qstory.backend.common.error;

/** Mirrors the Node backend's {@code ProviderError} class in {@code provider-error.mjs}. */
public class ProviderException extends RuntimeException {

    private final ProviderErrorCode code;
    private final String stage;
    private final String safeDetail;
    private final boolean retryable;

    public ProviderException(ProviderErrorCode code, String safeDetail) {
        this(code, code.stage(), safeDetail, code.defaultRetryable(), null);
    }

    public ProviderException(ProviderErrorCode code, String safeDetail, boolean retryable) {
        this(code, code.stage(), safeDetail, retryable, null);
    }

    public ProviderException(ProviderErrorCode code, String safeDetail, boolean retryable, Throwable cause) {
        this(code, code.stage(), safeDetail, retryable, cause);
    }

    /** Escape hatch for the handful of call sites whose stage varies by context (e.g. a timeout during stt vs. response). */
    public ProviderException(ProviderErrorCode code, String stage, String safeDetail, boolean retryable) {
        this(code, stage, safeDetail, retryable, null);
    }

    public ProviderException(
            ProviderErrorCode code, String stage, String safeDetail, boolean retryable, Throwable cause) {
        super(safeDetail, cause);
        this.code = code;
        this.stage = stage;
        this.safeDetail = safeDetail;
        this.retryable = retryable;
    }

    public ProviderErrorCode code() {
        return code;
    }

    public String stage() {
        return stage;
    }

    public String safeDetail() {
        return safeDetail;
    }

    public boolean retryable() {
        return retryable;
    }
}
