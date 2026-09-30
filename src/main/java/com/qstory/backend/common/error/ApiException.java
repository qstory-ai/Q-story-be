package com.qstory.backend.common.error;

/**
 * 파이프라인이 실행되기 전에 걸러지는 요청 형식 위반, 인증/인가 실패 등에 던진다. provider/pipeline
 * 실패에는 쓰지 않는다 - 그런 경우는 ProviderException으로 HTTP 200 + {@code {ok:false, failure:{...}}}
 * 바디가 된다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final String safeDetail;
    private final int statusCode;

    private ApiException(ErrorCode code, String safeDetail, int statusCode) {
        super(safeDetail);
        this.code = code;
        this.safeDetail = safeDetail;
        this.statusCode = statusCode;
    }

    public static ApiException contractError(ErrorCode code, String safeDetail) {
        return new ApiException(code, safeDetail, code.defaultStatus());
    }

    public static ApiException contractError(ErrorCode code, String safeDetail, int statusCode) {
        return new ApiException(code, safeDetail, statusCode);
    }

    public ErrorCode code() {
        return code;
    }

    public String safeDetail() {
        return safeDetail;
    }

    public int statusCode() {
        return statusCode;
    }
}
