package com.qstory.backend.provider.gemini.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 나레이션 모델(narration-tts-model)을 쓰는 GeminiTtsClient 두 개. 기본 인스턴스는 실시간 답 음성용(tts-model)이다.
 * 나레이션은 미리 녹음한 고정 음성 사이에 끼므로 빠르기보다 음질을 본다.
 */
@Configuration
public class GeminiTtsConfig {

    /** 선택지 음성 미리 만들기 전용 키. 키가 비어 있으면 서비스가 호출 전에 막는다. */
    @Bean
    public GeminiTtsClient prefetchGeminiTtsClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        AppProperties.Gemini gemini = config.providers().gemini();
        return new GeminiTtsClient(
                httpClient, objectMapper, config, gemini.narrationTtsModelOrDefault(), gemini.prefetchApiKey(),
                config.voiceStreaming().tts(), config.voiceLoudness().targetOrNull());
    }

    /** 관리자 나레이션 다시 만들기(NarrationRerenderService). 기본 키를 쓴다. */
    @Bean
    public GeminiTtsClient narrationGeminiTtsClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        AppProperties.Gemini gemini = config.providers().gemini();
        return new GeminiTtsClient(
                httpClient, objectMapper, config, gemini.narrationTtsModelOrDefault(), gemini.apiKey(),
                config.voiceStreaming().tts(), config.voiceLoudness().targetOrNull());
    }
}
