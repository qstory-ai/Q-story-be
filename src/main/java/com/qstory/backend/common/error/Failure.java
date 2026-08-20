package com.qstory.backend.common.error;

public record Failure(String code, String stage, boolean retryable, String safeDetail) {

    public static Failure of(ProviderException error) {
        return new Failure(error.code().name(), error.stage(), error.retryable(), error.safeDetail());
    }

    public static Failure of(ProviderErrorCode code, String stage, boolean retryable, String safeDetail) {
        return new Failure(code.name(), stage, retryable, safeDetail);
    }
}
