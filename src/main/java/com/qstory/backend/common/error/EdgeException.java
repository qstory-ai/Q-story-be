package com.qstory.backend.common.error;

public class EdgeException extends RuntimeException {

    private final EdgeErrorCode code;
    private final int statusCode;
    private final String stage;

    public EdgeException(EdgeErrorCode code) {
        this(code, code.defaultStatus(), null);
    }

    public EdgeException(EdgeErrorCode code, int statusCode) {
        this(code, statusCode, null);
    }

    public EdgeException(EdgeErrorCode code, int statusCode, String stage) {
        super(code.wireCode());
        this.code = code;
        this.statusCode = statusCode;
        this.stage = stage;
    }

    public EdgeErrorCode code() {
        return code;
    }

    public int statusCode() {
        return statusCode;
    }

    public String stage() {
        return stage;
    }
}
