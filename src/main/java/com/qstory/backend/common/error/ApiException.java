package com.qstory.backend.common.error;

/**
 * Node 백엔드의 {@code contractError(code, safeDetail, statusCode)} 헬퍼를 그대로 옮긴 것이다.
 * 파이프라인이 실행되기 전에 걸러지는 요청 형식 위반(검증, 크기 제한, CORS, 알 수 없는 라우트)에
 * 대해 던져지며, provider/pipeline 실패에 대해서는 절대 사용하지 않는다 - 그런 경우는 대신
 * HTTP 200과 함께 {@code {ok:false, failure:{...}}} 형태의 바디로 반환된다.
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
