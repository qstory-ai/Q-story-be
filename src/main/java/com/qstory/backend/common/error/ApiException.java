package com.qstory.backend.common.error;

/**
 * Mirrors the Node backend's {@code contractError(code, safeDetail, statusCode)} helper.
 * Thrown for request-shape violations caught before a pipeline runs (validation, size
 * limits, CORS, unknown routes) - never for provider/pipeline failures, which are
 * returned as {@code {ok:false, failure:{...}}} bodies with HTTP 200 instead.
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
