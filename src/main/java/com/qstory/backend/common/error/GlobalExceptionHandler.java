package com.qstory.backend.common.error;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * status >= 500 forces stage=routing/retryable=true and withholds the safeDetail (it describes a
 * server-side defect, so it goes to the log instead of to whoever called); anything below 500
 * passes the thrown safeDetail through as-is.
 *
 * Withheld rather than replaced with fixed copy: this advice covers every endpoint, and each
 * caller already has its own fallback wording for a detail-less failure - the story runtime picks
 * child-safe copy per stage (fe runtime-view.ts) and auth-api.ts falls back to a plain
 * "요청을 처리하지 못했어요". A message written here would override both with copy chosen without
 * knowing which endpoint failed, which is how a signup error came to say
 * "준비된 이야기로 계속할게요".
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<FailureBody> handleApiException(ApiException error) {
        // A 5xx ApiException is a server-side defect (misconfiguration, dependency down), not a
        // caller mistake, and respond() withholds its safeDetail - so without this the one sentence
        // describing what actually broke would reach nobody, on either side.
        if (error.statusCode() >= 500) {
            log.error("request.failed requestId={} code={} safeDetail={}",
                    currentRequestId(), error.code(), error.safeDetail(), error);
        }
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
                serverError ? null : safeDetail);
        // No x-qstory-request-id here: RequestIdFilter already setHeader()s it on the way in, so
        // adding it again emitted the same id twice on every error response.
        return ResponseEntity.status(HttpStatus.valueOf(statusCode)).body(FailureBody.of(failure));
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
