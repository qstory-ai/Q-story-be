package com.qstory.backend.common.error;

/** Node 백엔드의 {@code provider-error.mjs}에 있는 {@code ProviderError} 클래스를 그대로 옮긴 것이다. */
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

    /** stage가 상황에 따라 달라지는 소수의 호출부(예: stt 도중 타임아웃인지 response 도중 타임아웃인지)를 위한 탈출구다. */
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
