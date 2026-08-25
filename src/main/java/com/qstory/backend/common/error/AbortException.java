package com.qstory.backend.common.error;

/** {@link com.qstory.backend.config.AppProperties#requestTimeoutMs()}가 경과하여 요청이 중단됐을 때 발생한다. */
public class AbortException extends RuntimeException {

    public AbortException(String message) {
        super(message);
    }
}
