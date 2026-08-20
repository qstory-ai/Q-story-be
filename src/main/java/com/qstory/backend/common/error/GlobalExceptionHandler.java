package com.qstory.backend.common.error;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Uniform failure envelope for every request-shape violation caught before a pipeline runs.
 * Mirrors the catch-all block at the bottom of the Node backend's server.mjs request handler:
 * status >= 500 always downgrades the safeDetail to a fixed, child-safe message and forces
 * stage=routing/retryable=true; anything below 500 passes the thrown safeDetail through as-is.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INTERNAL_SAFE_DETAIL = "질문을 처리하지 못했어요. 준비된 이야기로 계속할게요.";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<FailureBody> handleApiException(ApiException error) {
        return respond(error.statusCode(), error.code(), error.safeDetail());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<FailureBody> handleNotFound() {
        return respond(404, ErrorCode.NOT_FOUND, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FailureBody> handleUnexpected(Exception error) {
        log.error("request.failed requestId={}", currentRequestId(), error);
        return respond(500, ErrorCode.INTERNAL_ERROR, null);
    }

    private ResponseEntity<FailureBody> respond(int statusCode, ErrorCode code, String safeDetail) {
        boolean serverError = statusCode >= 500;
        Failure failure = new Failure(
                code.name(),
                serverError ? "routing" : "upload",
                serverError,
                serverError ? INTERNAL_SAFE_DETAIL : safeDetail);
        return ResponseEntity.status(HttpStatus.valueOf(statusCode))
                .headers(this::withRequestId)
                .body(FailureBody.of(failure));
    }

    private void withRequestId(HttpHeaders headers) {
        headers.add("x-qstory-request-id", currentRequestId());
    }

    private String currentRequestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            Object existing = servletAttributes.getRequest().getAttribute("qstoryRequestId");
            if (existing instanceof String requestId) {
                return requestId;
            }
        }
        return UUID.randomUUID().toString();
    }
}
