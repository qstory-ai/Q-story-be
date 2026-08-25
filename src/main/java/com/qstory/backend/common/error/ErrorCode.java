package com.qstory.backend.common.error;

/**
 * {@link ApiException}으로 던져지는 모든 코드 - 파이프라인이 실행되기 전에 걸러지는 요청 형식
 * 위반(검증, 크기 제한, CORS, 알 수 없는 라우트)이다. server.mjs, question-contract.mjs,
 * narration-contract.mjs, story-registry.mjs의 contractError() 호출부에서 취합했다.
 * defaultStatus는 그 호출부들이 실제로 사용했던 HTTP 상태 코드다.
 */
public enum ErrorCode {
    MISSING_REQUEST_CONTEXT(400),
    INVALID_REQUEST_CONTEXT(400),
    UNSUPPORTED_AUDIO_TYPE(415),
    INVALID_TEXT_QUESTION(400),
    ORIGIN_NOT_ALLOWED(403),
    AUDIO_TOO_LARGE(413),
    EMPTY_AUDIO(400),
    UNSUPPORTED_CONTENT_TYPE(415),
    NARRATION_REQUEST_TOO_LARGE(413),
    INVALID_JSON(400),
    INVALID_BASE64_AUDIO_UPLOAD(400),
    STORY_NOT_REGISTERED(404),
    STORY_NOT_AVAILABLE(403),
    STORY_CONTEXT_NOT_ALLOWED(400),
    NARRATION_STORY_NOT_ALLOWED(403),
    NARRATION_SPEAKER_NOT_ALLOWED(403),
    NARRATION_VOICE_NOT_ALLOWED(403),
    INVALID_NARRATION_REQUEST(400),
    INVALID_NARRATION_TEXT(400),
    NOT_FOUND(404),
    INTERNAL_ERROR(500),
    INVALID_COMPANION_CHAT_REQUEST(400),
    COMPANION_CHAT_RATE_LIMITED(429),

    UNAUTHENTICATED(401),
    FORBIDDEN(403),
    VALIDATION_FAILED(400),
    INVALID_CREDENTIALS(401),
    LOGIN_ID_ALREADY_REGISTERED(409),
    INVALID_JOIN_CODE(404),
    INVALID_INVITE(410),
    INVALID_PASSWORD_RESET_TOKEN(410),
    ORGANIZATION_ALREADY_EXISTS(409),
    ORGANIZATION_NOT_CREATED(404),
    /** 에디터가 스토리를 불러온 이후 그 스토리가 다시 수정된 상태에서 시도된 저작(authoring) 쓰기 작업. */
    STALE_REVISION(409),
    ENTITLEMENT_REQUIRED(402),

    // 예전에는 별도의 EdgeErrorCode/EdgeException 체계({ok:false, error:'snake_case'} 응답 형태)로
    // 처리되던 코드들 - beta-events/voice-research/story-import가 옛 Supabase Edge Function을 그대로
    // 이식하면서 그 응답 형태까지 함께 들여온 것이었는데, 실제로 이 형태를 파싱하는 프론트엔드
    // 소비자가 하나도 없어서(다들 response.ok만 확인) 굳이 두 체계를 유지할 이유가 없었다. 여기로
    // 합쳐서 모든 요청 검증 실패가 하나의 {ok:false, failure:{code,...}} 형태로 응답한다.
    PAYLOAD_TOO_LARGE(413),
    INVALID_PAYLOAD(400),
    UNSUPPORTED_FIELD(400),
    RATE_LIMITED(429),
    INVALID_CONSENT_TIME(400),
    INVALID_FORM_DATA(400),
    STORAGE_FAILED(500),
    CONSENT_INVALID(403),
    UNSUPPORTED_ACTION(400);

    private final int defaultStatus;

    ErrorCode(int defaultStatus) {
        this.defaultStatus = defaultStatus;
    }

    public int defaultStatus() {
        return defaultStatus;
    }
}
