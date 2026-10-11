package com.qstory.backend.identity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.qstory.backend.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

/** 실시간 받아 적기 WebSocket 업그레이드는 웹 출처도 직접 붙으므로 ALLOWED_ORIGINS가 좁아도 웹·앱 출처를 받는다. */
class SecurityConfigSttStreamCorsTest {

    private final SecurityConfig config = new SecurityConfig(
            new AppProperties(
                    List.of("https://dev.qstory.ai.kr"), List.of("https://app.qstory.ai.kr", "capacitor://app.qstory.ai.kr"),
                    0, 0, null, null, null, null, null, null, null),
            null);

    private CorsConfiguration corsFor(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        return config.corsConfigurationSource().getCorsConfiguration(request);
    }

    @Test
    void streamPathAcceptsWebAndAppOriginsEvenWithANarrowEnvList() {
        CorsConfiguration stream = corsFor("/v1/stt/stream");
        assertEquals("https://demo.qstory.ai.kr", stream.checkOrigin("https://demo.qstory.ai.kr"));
        assertEquals("https://app.qstory.ai.kr", stream.checkOrigin("https://app.qstory.ai.kr"));
        assertEquals("https://dev.qstory.ai.kr", stream.checkOrigin("https://dev.qstory.ai.kr"));
        assertNull(stream.checkOrigin("https://evil.example"));
    }

    @Test
    void otherPathsKeepTheEnvironmentList() {
        CorsConfiguration other = corsFor("/v1/companion-chat/messages");
        assertNull(other.checkOrigin("https://demo.qstory.ai.kr"));
        assertEquals("https://app.qstory.ai.kr", other.checkOrigin("https://app.qstory.ai.kr"));
    }
}
