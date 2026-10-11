package com.qstory.backend.identity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.qstory.backend.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityConfigTest {

    private static AppProperties properties(List<String> allowed, List<String> nativeOrigins) {
        return new AppProperties(allowed, nativeOrigins, 0, 0, null, null, null, null, null, null, null);
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

    /** 보안 점검 L6: HSTS는 https로 받은 요청(프록시가 알려 준 경우 포함)에만 붙인다. */
    @Test
    void httpsIsDetectedDirectlyOrFromTheProxyHeader() {
        org.springframework.mock.web.MockHttpServletRequest plain = new org.springframework.mock.web.MockHttpServletRequest();
        org.junit.jupiter.api.Assertions.assertFalse(SecurityConfig.isHttps(plain));

        org.springframework.mock.web.MockHttpServletRequest proxied = new org.springframework.mock.web.MockHttpServletRequest();
        proxied.addHeader("X-Forwarded-Proto", "https");
        org.junit.jupiter.api.Assertions.assertTrue(SecurityConfig.isHttps(proxied));

        org.springframework.mock.web.MockHttpServletRequest secure = new org.springframework.mock.web.MockHttpServletRequest();
        secure.setSecure(true);
        org.junit.jupiter.api.Assertions.assertTrue(SecurityConfig.isHttps(secure));
    }
}
