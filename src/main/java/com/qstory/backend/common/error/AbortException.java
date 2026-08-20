package com.qstory.backend.common.error;

/** Raised when a request is aborted because {@link com.qstory.backend.config.AppProperties#requestTimeoutMs()} elapsed. */
public class AbortException extends RuntimeException {

    public AbortException(String message) {
        super(message);
    }
}
