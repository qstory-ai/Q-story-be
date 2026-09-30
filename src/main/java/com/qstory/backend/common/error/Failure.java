package com.qstory.backend.common.error;

public record Failure(String code, String stage, boolean retryable, String safeDetail) {}
