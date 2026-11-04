package com.qstory.backend.common.log;

/**
 * Grafana(ops/grafana-alerting/*.tf)가 LogQL `|= "..."`로 잡는 로그 이벤트 이름. 알림과 대시보드가 이 문자열에 묶여 있으니
 * 로그를 바꿀 때 여기 상수를 쓰고, LogEventsGrafanaContractTest가 .tf에 적힌 이름이 전부 여기 있는지 확인한다.
 */
public final class LogEvents {

    private LogEvents() {}

    /** 요청마다 한 줄(RequestIdFilter) - 5xx 비율·지연 알림과 응답 대시보드의 바탕. */
    public static final String HTTP_REQUEST = "http.request";

    /** 살아 있음 신호(HeartbeatLogger) - 끊기면 인스턴스가 죽은 것. */
    public static final String APP_HEARTBEAT = "app.heartbeat";

    /** 처리되지 않은 예외(GlobalExceptionHandler). */
    public static final String REQUEST_FAILED = "request.failed";

    /** 브라우저가 보낸 에러(ClientErrorController). */
    public static final String CLIENT_ERROR = "client.error";

    /** 요청 상한에 걸림(RateLimitService). */
    public static final String RATE_LIMIT_BLOCKED = "rate-limit.blocked";

    /** OpenRouter HTTP 실패(OpenRouterClient). */
    public static final String OPENROUTER_HTTP_FAILED = "openrouter-http.failed";

    /** Gemini TTS HTTP 실패(GeminiTtsClient). */
    public static final String GEMINI_HTTP_FAILED = "gemini-http.failed";

    /** Gemini TTS 성공 한 건 - 하루 한도 집계. */
    public static final String GEMINI_TTS_OK = "gemini-tts.ok";

    /** Gemini가 소리 없이 답함. */
    public static final String GEMINI_TTS_EMPTY_AUDIO = "gemini-tts.empty-audio";

    /** Gemini 스트리밍 도중 실패. */
    public static final String GEMINI_TTS_STREAM_FAILED = "gemini-tts.stream-failed";

    /** RTZR STT HTTP 실패(RtzrSttClient). */
    public static final String RTZR_HTTP_FAILED = "rtzr-http.failed";

    /** RTZR가 전사 실패를 돌려줌. */
    public static final String RTZR_TRANSCRIPTION_FAILED = "rtzr-transcription.failed";

    /** 푸시 전송 실패(FcmClient·PushDispatcher). */
    public static final String FCM_SEND_FAILED = "fcm.send-failed";

    /** 대화 기록 보존 정리 실패. */
    public static final String COMPANION_CHAT_RETENTION_FAILED = "companion-chat-retention.failed";

    /** 테스트용 - 여기 적힌 이름 전부. */
    public static final java.util.Set<String> ALL = java.util.Set.of(
            HTTP_REQUEST, APP_HEARTBEAT, REQUEST_FAILED, CLIENT_ERROR, RATE_LIMIT_BLOCKED, OPENROUTER_HTTP_FAILED, GEMINI_HTTP_FAILED, GEMINI_TTS_OK, GEMINI_TTS_EMPTY_AUDIO, GEMINI_TTS_STREAM_FAILED, RTZR_HTTP_FAILED, RTZR_TRANSCRIPTION_FAILED, FCM_SEND_FAILED, COMPANION_CHAT_RETENTION_FAILED);
}
