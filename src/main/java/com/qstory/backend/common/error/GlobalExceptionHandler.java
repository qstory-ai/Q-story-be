package com.qstory.backend.common.error;

import com.qstory.backend.common.web.RequestIdFilter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 모든 요청 형식 위반·예기치 못한 오류에 대한 통일된 실패 봉투(envelope). status가 500 이상이면
 * stage=routing/retryable=true로 강제하고 safeDetail을 숨긴다(서버 측 결함 설명은 호출자가 아니라
 * 로그로 보낸다); 500 미만이면 던져진 safeDetail을 그대로 전달한다.
 *
 * <p>고정 문구로 대체하지 않고 숨기기만 하는 이유: 이 advice는 모든 엔드포인트를 포괄하는데, 호출자마다
 * detail이 없는 실패에 대한 자기 폴백 문구가 있다(스토리 런타임은 단계별 아동용 문구, auth 화면은
 * "요청을 처리하지 못했어요"). 여기서 문구를 정하면 어느 엔드포인트인지 모른 채 고른 문구가 그 둘을
 * 덮어써, 회원가입 에러가 "준비된 이야기로 계속할게요"라고 말하게 된다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<FailureBody> handleApiException(ApiException error) {
        // 5xx ApiException은 호출자의 실수가 아니라 서버 측 결함(설정 오류, 의존 서비스 장애
        // 등)이며, respond()는 그 safeDetail을 숨긴다 - 그러므로 이 로그가 없으면 실제로 무엇이
        // 고장났는지 설명하는 그 한 문장이 양쪽 어디에도 도달하지 못한다.
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

    /** 있는 경로에 지원하지 않는 메서드(없앤 엔드포인트 등) - 서버 결함이 아니므로 500이 아니라 405로 답한다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<FailureBody> handleMethodNotAllowed() {
        return respond(405, ErrorCode.METHOD_NOT_ALLOWED, null);
    }

    /**
     * 요청 형식이 틀린 경우(필수 값·파트 누락, 숫자 자리에 글자, 깨진 multipart·JSON) - 호출자의 실수라 500이 아니라 400으로
     * 답한다(Q-41: 형식이 틀린 음성 업로드가 500으로 떨어져 서버 오류 알림까지 울리던 문제).
     */
    @ExceptionHandler({
            ServletRequestBindingException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            MultipartException.class
    })
    public ResponseEntity<FailureBody> handleMalformedRequest() {
        return respond(400, ErrorCode.VALIDATION_FAILED, "요청 형식이 올바르지 않아요.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<FailureBody> handleUnreadableBody() {
        return respond(400, ErrorCode.INVALID_JSON, "요청 형식이 올바르지 않아요.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<FailureBody> handleUploadTooLarge() {
        return respond(413, ErrorCode.PAYLOAD_TOO_LARGE, "파일이 너무 커요.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<FailureBody> handleUnsupportedMediaType() {
        return respond(415, ErrorCode.UNSUPPORTED_CONTENT_TYPE, "요청 형식이 올바르지 않아요.");
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
        // 여기서는 x-qstory-request-id를 넣지 않는다: RequestIdFilter가 들어오는 길에 이미
        // setHeader()로 설정해두므로, 여기서 또 추가하면 모든 에러 응답에 같은 id가 두 번씩
        // 찍히게 된다.
        return ResponseEntity.status(HttpStatus.valueOf(statusCode)).body(FailureBody.of(failure));
    }

    private String currentRequestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            Object existing = servletAttributes.getRequest().getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            if (existing instanceof String requestId) {
                return requestId;
            }
        }
        return UUID.randomUUID().toString();
    }
}
