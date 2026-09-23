package com.qstory.backend.identity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.qstory.backend.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityConfigTest {

    private static AppProperties properties(List<String> allowed, List<String> nativeOrigins) {
        return new AppProperties(allowed, nativeOrigins, 0, 0, null, null, null, null, null, null);
    }

    @Test
    void nativeOriginsAreAlwaysAppendedToTheEnvironmentList() {
        SecurityConfig config = new SecurityConfig(
                properties(List.of("https://qstory.ai.kr"), List.of("https://app.qstory.ai.kr", "capacitor://app.qstory.ai.kr")),
                null);

        assertEquals(
                List.of("https://qstory.ai.kr", "https://app.qstory.ai.kr", "capacitor://app.qstory.ai.kr"),
                config.effectiveAllowedOrigins());
    }

    @Test
    void duplicatesAndMissingListsAreTolerated() {
        SecurityConfig config = new SecurityConfig(
                properties(List.of("https://app.qstory.ai.kr"), List.of("https://app.qstory.ai.kr")), null);
        assertEquals(List.of("https://app.qstory.ai.kr"), config.effectiveAllowedOrigins());

        SecurityConfig noNative = new SecurityConfig(properties(List.of("https://qstory.ai.kr"), null), null);
        assertEquals(List.of("https://qstory.ai.kr"), noNative.effectiveAllowedOrigins());
    }
}
