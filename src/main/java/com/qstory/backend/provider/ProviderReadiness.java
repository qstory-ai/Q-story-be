package com.qstory.backend.provider;

import com.qstory.backend.config.AppProperties;

public record ProviderReadiness(boolean stt, boolean llm, boolean tts, boolean image) {

    public static ProviderReadiness of(AppProperties config) {
        return new ProviderReadiness(
                config.providers().rtzr().configured(),
                config.providers().openRouter().llmConfigured(),
                config.providers().openRouter().ttsConfigured(),
                config.providers().openRouter().imageConfigured());
    }
}
