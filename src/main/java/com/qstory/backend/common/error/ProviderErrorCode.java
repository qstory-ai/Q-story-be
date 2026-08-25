package com.qstory.backend.common.error;

/**
 * {@link ProviderException}으로 던져지거나 반환되는, 또는 {@code {ok:false, failure:{...}}}
 * 파이프라인 응답 바디에 직접 포함되는 모든 코드다. question-pipeline.mjs, narration-pipeline.mjs,
 * providers/*.mjs 전반의 provider-error.mjs 호출부에서 취합했다.
 *
 * <p>{@code defaultRetryable}은 각 코드의 가장 흔한 호출부 상황을 반영한다. 몇몇 호출부는
 * upstream HTTP 상태로부터 retryable 여부를 동적으로 계산하는데(예: OPENROUTER_TTS_FAILED는
 * upstream이 >=429로 응답했을 때만 retryable이다) - 그런 경우에는 이 기본값에 의존하는 대신
 * {@link ProviderException}의 3개 인자 팩토리를 호출해서 기본값을 재정의한다.
 */
public enum ProviderErrorCode {
    // 파이프라인 레벨(question-pipeline.mjs / narration-pipeline.mjs). provider가 직접 던지는 일은 없다.
    NARRATION_PROVIDER_NOT_CONFIGURED("tts", false),
    NARRATION_VOICE_NOT_ALLOWED("tts", false),
    NARRATION_TIMEOUT("tts", true),
    NARRATION_GENERATION_FAILED("tts", true),
    NARRATION_STREAM_FAILED("tts", true),
    STT_PROVIDER_NOT_CONFIGURED("stt", false),
    NO_SPEECH_DETECTED("stt", true),
    SPEECH_PROVIDER_NOT_CONFIGURED("stt", false),
    RESPONSE_PROVIDER_NOT_CONFIGURED("response", false),
    OPENROUTER_RESPONSE_MISSING("response", true),
    SPEECH_PIPELINE_TIMEOUT("response", true),
    SPEECH_PIPELINE_FAILED("response", true),
    SPEECH_PIPELINE_TTS_TIMEOUT("tts", true),
    SPEECH_PIPELINE_TTS_FAILED("tts", true),

    // OpenRouter LLM 라우팅 (providers/openrouter.mjs generatePlan()).
    STORY_CONTEXT_NOT_ALLOWED("routing", false),
    OPENROUTER_RESPONSE_FAILED("response", true),
    OPENROUTER_RESPONSE_INVALID("response", true),
    OPENROUTER_SECOND_CLARIFICATION("response", true),

    // OpenRouter TTS (providers/openrouter.mjs synthesize()/synthesizeStream()).
    OPENROUTER_TTS_FAILED("tts", true),
    OPENROUTER_TTS_EMPTY("tts", true),
    OPENROUTER_TTS_NETWORK_FAILED("tts", true),
    OPENROUTER_TTS_STREAM_INVALID("tts", true),

    // OpenRouter 이미지 생성 (provider/openrouter/util/OpenRouterClient.java generateImage() - shadow-family 삽화 전용).
    OPENROUTER_IMAGE_FAILED("image", true),
    OPENROUTER_IMAGE_EMPTY("image", true),
    OPENROUTER_IMAGE_INVALID("image", true),
    OPENROUTER_IMAGE_NETWORK_FAILED("image", true),

    // RTZR STT (providers/rtzr-stt.mjs).
    RTZR_AUTH_FAILED("stt", true),
    RTZR_SUBMIT_FAILED("stt", true),
    RTZR_RESULT_FAILED("stt", true),
    RTZR_TRANSCRIPTION_FAILED("stt", true),
    RTZR_NETWORK_FAILED("stt", true),

    // 오디오 정규화 (providers/audio-normalizer.mjs).
    AUDIO_NORMALIZATION_UNSUPPORTED("normalization", false),
    AUDIO_NORMALIZATION_FAILED("normalization", true);

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
