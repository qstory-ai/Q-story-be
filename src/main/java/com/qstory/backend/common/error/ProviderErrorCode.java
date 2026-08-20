package com.qstory.backend.common.error;

/**
 * Every code thrown/returned as a {@link ProviderException} or embedded directly in a
 * {@code {ok:false, failure:{...}}} pipeline response body. Consolidated from provider-error.mjs
 * call sites across question-pipeline.mjs, narration-pipeline.mjs, and providers/*.mjs.
 *
 * <p>{@code defaultRetryable} reflects each code's most common call site. Several call sites
 * compute retryable dynamically from an upstream HTTP status (e.g. OPENROUTER_TTS_FAILED is
 * retryable only when the upstream responded >=429) - those call {@link ProviderException}'s
 * 3-arg factory to override this default instead of relying on it.
 */
public enum ProviderErrorCode {
    // Pipeline-level (question-pipeline.mjs / narration-pipeline.mjs), never thrown by a provider.
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

    // OpenRouter LLM routing (providers/openrouter.mjs generatePlan()).
    STORY_CONTEXT_NOT_ALLOWED("routing", false),
    OPENROUTER_RESPONSE_FAILED("response", true),
    OPENROUTER_RESPONSE_INVALID("response", true),
    OPENROUTER_SECOND_CLARIFICATION("response", true),

    // OpenRouter TTS (providers/openrouter.mjs synthesize()/synthesizeStream()).
    OPENROUTER_TTS_FAILED("tts", true),
    OPENROUTER_TTS_EMPTY("tts", true),
    OPENROUTER_TTS_NETWORK_FAILED("tts", true),
    OPENROUTER_TTS_STREAM_INVALID("tts", true),

    // RTZR STT (providers/rtzr-stt.mjs).
    RTZR_AUTH_FAILED("stt", true),
    RTZR_SUBMIT_FAILED("stt", true),
    RTZR_RESULT_FAILED("stt", true),
    RTZR_TRANSCRIPTION_FAILED("stt", true),
    RTZR_NETWORK_FAILED("stt", true),

    // Audio normalization (providers/audio-normalizer.mjs).
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
