package com.qstory.backend.common.error;

/**
 * {@link ApiException}으로 던져지는 모든 코드 - 파이프라인이 실행되기 전에 걸러지는 요청 형식
 * 위반, 인증/인가 실패 등. 모두 {ok:false, failure:{code,...}} 한 가지 형태로 응답된다.
 */
public enum ErrorCode {
    MISSING_REQUEST_CONTEXT(400),
    INVALID_REQUEST_CONTEXT(400),
    UNSUPPORTED_AUDIO_TYPE(415),
    INVALID_TEXT_QUESTION(400),
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
    METHOD_NOT_ALLOWED(405),
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
    OAUTH_TOKEN_INVALID(401),
    OAUTH_ROLE_REQUIRED(400),
    OAUTH_EMAIL_ALREADY_REGISTERED(409),
    OAUTH_PROVIDER_NOT_CONFIGURED(503),
    /** 에디터가 스토리를 불러온 이후 그 스토리가 다시 수정된 상태에서 시도된 저작(authoring) 쓰기 작업. */
    STALE_REVISION(409),
    ENTITLEMENT_REQUIRED(402),

    PAYLOAD_TOO_LARGE(413),
    INVALID_PAYLOAD(400),
    UNSUPPORTED_FIELD(400),
    RATE_LIMITED(429),
    INVALID_CONSENT_TIME(400),
    INVALID_FORM_DATA(400),
    STORAGE_FAILED(500),
    PAYMENT_PROVIDER_UNAVAILABLE(503),
    PAYMENT_CONFIRMATION_FAILED(422),
    CONSENT_INVALID(403),
    /** 가입 때 이용약관·개인정보 동의가 없다. */
    CONSENT_REQUIRED(400),
    /** 음성 인식 업체의 결제·인증·권한 문제처럼 재시도로 풀리지 않는 장애. */
    STT_UNAVAILABLE(503),
    /** 초대 수락 시 같은 이름의 아이가 여러 명이라 부모가 childId로 골라야 한다. */
    CHILD_SELECTION_REQUIRED(409),
    /** 같은 선생님의 다른 학생 등록에 이미 연결된 아이 프로필. */
    DUPLICATE_CHILD_LINK(409),
    /** 선생님 운영 반에 반 코드로 들어올 때 학생 명단에 올릴 아이 이름·출생연도가 필요하다. */
    CHILD_INFO_REQUIRED(400),
    /** 선택지 음성 미리 만들기 전용 Gemini 키가 설정되지 않았다 - TTS 호출 없이 거절한다. */
    PREFETCH_DISABLED(409),
    /** 화면 녹화 동의가 없거나 철회됐다 - 녹화 조각을 받지 않는다. */
    RECORDING_NOT_CONSENTED(403),
    /** 지난 반(보관된 반)이라 담임 배정·초대·학생 옮겨 넣기를 할 수 없다(076). */
    CLASS_ARCHIVED(409),
    /** 반에 아직 지금 학생이 있어 보관할 수 없다 - 학기 넘기기로 옮기거나 졸업 처리해야 한다(076). */
    CLASS_HAS_ACTIVE_STUDENTS(409);

    private final int defaultStatus;

    ErrorCode(int defaultStatus) {
        this.defaultStatus = defaultStatus;
    }

    public int defaultStatus() {
        return defaultStatus;
    }
}
