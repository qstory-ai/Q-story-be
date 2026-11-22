package com.qstory.backend.provider;

import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;

/**
 * 외부 공급자(OpenRouter·Gemini·RTZR) HTTP 호출이 공유하는 실패 처리. 세 클라이언트가 같은 로그 함수와 같은 catch 사다리를
 * 각자 복사해 쓰던 것을 모았다.
 *
 * <ul>
 *   <li>{@link #logHttpFailure} - 2xx가 아닌 응답의 실제 원인을 서버 로그에 남긴다. 사용자에게는 고정 안전 문구만 내려가므로
 *       이 로그가 없으면 401/400 같은 설정 오류인지조차 운영 중엔 알 수 없다. API 키는 헤더에만 실리므로 본문을 남겨도 새지 않는다.
 *       태그(LogEvents)는 공급자마다 하나라 Grafana에서 `|= "openrouter-http.failed"` 하나로 그 공급자의 실패를 다 모은다.</li>
 *   <li>{@link #asProviderFailure} - catch(Exception) 한 번으로 끝내게 한다. 이미 분류된 예외(ProviderException·AbortException·
 *       ApiException)는 그대로, 시간 초과·인터럽트는 AbortException("request-timeout"), 나머지는 호출부가 준 코드의
 *       ProviderException.</li>
 * </ul>
 */
public final class ProviderHttp {

    /** 로그에 남길 실패 응답 본문의 최대 길이 - 전체를 남기면 로그가 지나치게 커질 수 있다. */
    public static final int FAILURE_BODY_LOG_LIMIT = 500;

    private ProviderHttp() {}

    /** 응답 본문을 로그용으로 자른다. */
    public static String snippet(byte[] responseBody) {
        String text = responseBody == null ? "" : new String(responseBody, StandardCharsets.UTF_8);
        return text.length() > FAILURE_BODY_LOG_LIMIT ? text.substring(0, FAILURE_BODY_LOG_LIMIT) + "...(truncated)" : text;
    }

    public static void logHttpFailure(Logger log, String event, String context, int statusCode, byte[] responseBody) {
        log.warn(event + " context={} status={} retryable={} responseBody={}",
                context, statusCode, statusCode >= 429, snippet(responseBody));
    }

    /** 예상 못 한 예외는 다시 시도할 수 있는 것으로 본다(네트워크 장애가 대부분). */
    public static RuntimeException asProviderFailure(Exception error, ProviderErrorCode code, String safeDetail) {
        return asProviderFailure(error, code, safeDetail, true);
    }

    /**
     * @param retryable 분류되지 않은 예외를 ProviderException으로 감쌀 때의 retryable - 응답 파싱 실패처럼 재시도해도
     *                  같은 결과일 것이 분명하면 false를 준다.
     */
    public static RuntimeException asProviderFailure(
            Exception error, ProviderErrorCode code, String safeDetail, boolean retryable) {
        if (error instanceof ProviderException || error instanceof AbortException || error instanceof ApiException) {
            return (RuntimeException) error;
        }
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new AbortException("request-timeout");
        }
        if (error instanceof HttpTimeoutException) {
            return new AbortException("request-timeout");
        }
        return new ProviderException(code, safeDetail, retryable, error);
    }

    /** {@link #asProviderFailure}가 "분류되지 않은 예외"로 다룬 것인지 - 그때만 남길 추가 로그가 있는 호출부용. */
    public static boolean isUnexpected(Exception error) {
        return !(error instanceof ProviderException || error instanceof AbortException || error instanceof ApiException
                || error instanceof InterruptedException || error instanceof HttpTimeoutException);
    }
}
