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
 * 파이프라인이 실행되기 전에 걸러지는 모든 요청 형식 위반에 대한 통일된 실패 봉투(envelope)다.
 * Node 백엔드 server.mjs 요청 핸들러 하단의 catch-all 블록을 그대로 옮긴 것이다: status가 500
 * 이상이면 stage=routing/retryable=true로 강제하고 safeDetail을 숨긴다(서버 측 결함을 설명하는
 * 내용이므로, 호출한 쪽이 아니라 로그로 보낸다); 500 미만이면 던져진 safeDetail을 그대로
 * 전달한다.
 *
 * 고정된 문구로 대체하지 않고 그냥 숨기는 이유: 이 advice는 모든 엔드포인트를 포괄하는데,
 * 각 호출자는 이미 detail이 없는 실패에 대한 자신만의 폴백 문구를 갖고 있다 - 스토리 런타임은
 * 단계별로 아동에게 안전한 문구를 고르고(fe runtime-view.ts), auth-api.ts는 단순히
 * "요청을 처리하지 못했어요"로 폴백한다. 여기서 메시지를 작성해버리면, 어느 엔드포인트가
 * 실패했는지 모르는 채로 고른 문구가 이 둘을 모두 덮어써 버리는데, 실제로 이런 식으로 회원가입
 * 에러가 "준비된 이야기로 계속할게요"라고 말하게 된 적이 있었다.
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
            Object existing = servletAttributes.getRequest().getAttribute("qstoryRequestId");
            if (existing instanceof String requestId) {
                return requestId;
            }
        }
        return UUID.randomUUID().toString();
    }
}
