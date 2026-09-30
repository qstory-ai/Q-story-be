package com.qstory.backend.common.error;

/**
 * {@link ProviderException}으로 던져지거나 {@code {ok:false, failure:{...}}} 파이프라인 응답
 * 바디에 직접 담기는 모든 코드.
 *
 * <p>{@code defaultRetryable}은 각 코드의 가장 흔한 호출부 상황이다. upstream HTTP 상태로
 * retryable을 정하는 호출부(예: GEMINI_TTS_FAILED는 >=429일 때만 retryable)는
 * {@link ProviderException}의 retryable 인자로 이 기본값을 재정의한다.
 */
public enum ProviderErrorCode {
    // 파이프라인 레벨. provider가 직접 던지는 일은 없다.
    NARRATION_PROVIDER_NOT_CONFIGURED("tts", false),
    NARRATION_VOICE_NOT_ALLOWED("tts", false),
    NARRATION_TIMEOUT("tts", true),
    STT_PROVIDER_NOT_CONFIGURED("stt", false),
    NO_SPEECH_DETECTED("stt", true),
    RESPONSE_PROVIDER_NOT_CONFIGURED("response", false),
    OPENROUTER_RESPONSE_MISSING("response", true),
    SPEECH_PIPELINE_TIMEOUT("response", true),
    SPEECH_PIPELINE_FAILED("response", true),
    SPEECH_PIPELINE_TTS_TIMEOUT("tts", true),
    SPEECH_PIPELINE_TTS_FAILED("tts", true),

    // OpenRouter LLM 라우팅.
    STORY_CONTEXT_NOT_ALLOWED("routing", false),
    OPENROUTER_RESPONSE_FAILED("response", true),
    OPENROUTER_RESPONSE_INVALID("response", true),
    OPENROUTER_SECOND_CLARIFICATION("response", true),

    // OpenRouter 이미지 생성 (OpenRouterClient.generateImage()).
    OPENROUTER_IMAGE_FAILED("image", true),
    OPENROUTER_IMAGE_EMPTY("image", true),
    OPENROUTER_IMAGE_INVALID("image", true),
    OPENROUTER_IMAGE_NETWORK_FAILED("image", true),

    // RTZR STT.
    RTZR_AUTH_FAILED("stt", true),
    RTZR_SUBMIT_FAILED("stt", true),
    RTZR_RESULT_FAILED("stt", true),
    RTZR_TRANSCRIPTION_FAILED("stt", true),
    RTZR_NETWORK_FAILED("stt", true),

    // 오디오 정규화 (AudioNormalizer).
    AUDIO_NORMALIZATION_UNSUPPORTED("normalization", false),
    AUDIO_NORMALIZATION_FAILED("normalization", true),

    // Gemini TTS (GeminiTtsClient) - OpenRouter를 거치지 않고 Gemini API를 직접 부른다.
    GEMINI_TTS_FAILED("tts", true),
    GEMINI_TTS_EMPTY("tts", true),
    GEMINI_TTS_NETWORK_FAILED("tts", true);

    private final String stage;
    private final boolean defaultRetryable;

    ProviderErrorCode(String stage, boolean defaultRetryable) {
        this.stage = stage;
        this.defaultRetryable = defaultRetryable;
    }

    public String stage() {
        return stage;
    }

    public boolean defaultRetryable() {
        return defaultRetryable;
    }
}
