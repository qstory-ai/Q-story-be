package com.qstory.backend.common.error;

public record FailureBody(boolean ok, Failure failure) {

    public static FailureBody of(Failure failure) {
        return new FailureBody(false, failure);
    }
}
