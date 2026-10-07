package com.qstory.backend.provider.gemini.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 선택지 음성 미리 만들기 전용 키로 만든 두 번째 GeminiTtsClient. 키가 비어 있으면 서비스가 호출 전에 막는다. */
@Configuration
public class GeminiTtsConfig {

    @Bean
    public GeminiTtsClient prefetchGeminiTtsClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        return new GeminiTtsClient(httpClient, objectMapper, config, config.providers().gemini().prefetchApiKey());
    }
}
