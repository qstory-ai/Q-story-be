package com.qstory.backend.common.error;

/** provider/파이프라인 실패. 요청 형식 위반(ApiException)과 달리 HTTP 200 + {ok:false, failure}로 응답된다. */
public class ProviderException extends RuntimeException {

    private final ProviderErrorCode code;
    private final String safeDetail;
    private final boolean retryable;

    public ProviderException(ProviderErrorCode code, String safeDetail) {
        this(code, safeDetail, code.defaultRetryable(), null);
    }

    public ProviderException(ProviderErrorCode code, String safeDetail, boolean retryable) {
        this(code, safeDetail, retryable, null);
    }

    public ProviderException(ProviderErrorCode code, String safeDetail, boolean retryable, Throwable cause) {
        super(safeDetail, cause);
        this.code = code;
        this.safeDetail = safeDetail;
        this.retryable = retryable;
    }

    public ProviderErrorCode code() {
        return code;
    }

    public String stage() {
        return code.stage();
    }

    public String safeDetail() {
        return safeDetail;
    }

    public boolean retryable() {
        return retryable;
    }
}
