package com.qstory.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class RateLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RateLimitFilter filter(Map<String, RateLimitProperties.Limit> limits, boolean enabled) {
        RateLimitProperties props = new RateLimitProperties(enabled, "", "", 80, limits, 1000);
        return new RateLimitFilter(new RateLimitService(props, Clock.systemUTC(), System::nanoTime), objectMapper);
    }

    private static MockHttpServletRequest request(String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void routesMapToTheirGroups() {
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/narrations")).containsExactly(RateLimitRule.TTS);
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/narrations/stream")).containsExactly(RateLimitRule.TTS);
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/companion-chat/messages"))
                .containsExactly(RateLimitRule.COMPANION_CHAT, RateLimitRule.COMPANION_CHAT_GLOBAL);
        for (String stt : List.of("/v1/transcriptions", "/v1/transcriptions/base64",
                "/v1/companion-chat/transcriptions/base64", "/v1/stt/stream-tickets")) {
            assertThat(RateLimitFilter.rulesFor("POST", stt)).containsExactly(RateLimitRule.STT, RateLimitRule.STT_GLOBAL);
        }
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/auth/login")).containsExactly(RateLimitRule.LOGIN_CLIENT);
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/auth/oauth/kakao")).containsExactly(RateLimitRule.LOGIN_CLIENT);
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/auth/signup/parent")).containsExactly(RateLimitRule.SIGNUP);
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/auth/signup/staff")).containsExactly(RateLimitRule.SIGNUP_STAFF);
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/auth/password-reset/request"))
                .containsExactly(RateLimitRule.PASSWORD_RESET_REQUEST);
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/play-sessions/" + java.util.UUID.randomUUID() + "/turns"))
                .containsExactly(RateLimitRule.PLAY_SESSION_TURNS);
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/session-recordings/chunks"))
                .containsExactly(RateLimitRule.RECORDING_CHUNKS);
        // 읽기·목록에 없는 경로는 세지 않는다.
        assertThat(RateLimitFilter.rulesFor("GET", "/v1/stories")).isEmpty();
        assertThat(RateLimitFilter.rulesFor("OPTIONS", "/v1/narrations")).isEmpty();
        assertThat(RateLimitFilter.rulesFor("POST", "/v1/auth/refresh")).isEmpty();
    }

    @Test
    void blockedRequestGets429EnvelopeWithRetryAfterAndNeverReachesTheController() throws Exception {
        RateLimitFilter filter = filter(Map.of("signup", new RateLimitProperties.Limit(1, Duration.ofHours(1))), true);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request("/v1/auth/signup/parent", "9.9.9.9"), new MockHttpServletResponse(), chain);
        verify(chain, times(1)).doFilter(any(), any());

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain blockedChain = mock(FilterChain.class);
        filter.doFilter(request("/v1/auth/signup/parent", "9.9.9.9"), response, blockedChain);

        verify(blockedChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("ok").asBoolean(true)).isFalse();
        assertThat(body.path("failure").path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.path("failure").path("retryable").asBoolean(true)).isFalse();
        assertThat(body.path("failure").path("safeDetail").asText()).isEqualTo("가입 요청이 너무 많아요. 1시간 뒤에 다시 시도해 주세요.");
    }

    @Test
    void globalRuleBlocksEveryAddressOnceSpent() throws Exception {
        RateLimitFilter filter = filter(Map.of("companion-chat-global", new RateLimitProperties.Limit(2, Duration.ofHours(1))), true);
        for (String ip : List.of("1.0.0.1", "1.0.0.2")) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(request("/v1/companion-chat/messages", ip), ok, mock(FilterChain.class));
            assertThat(ok.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request("/v1/companion-chat/messages", "1.0.0.3"), blocked, mock(FilterChain.class));
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("그레텔이 잠깐 쉬고 있어");
    }

    @Test
    void disabledPassesEverything() throws Exception {
        RateLimitFilter filter = filter(Map.of("signup", new RateLimitProperties.Limit(1, Duration.ofHours(1))), false);
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 5; i++) {
            filter.doFilter(request("/v1/auth/signup/parent", "9.9.9.9"), new MockHttpServletResponse(), chain);
        }
        verify(chain, times(5)).doFilter(any(), any());
    }

    @RestController
    static class ThrowingController {
        @PostMapping("/limited")
        void limited() {
            throw new RateLimitedException(RateLimitRule.LOGIN_ACCOUNT, 120);
        }
    }

    /** 컨트롤러가 던진 상한(로그인 아이디·익명 TTS 총량)도 필터와 같은 429 봉투로 나간다. */
    @Test
    void exceptionFromAControllerBecomesTheSame429Envelope() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(post("/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.failure.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.failure.safeDetail").value("로그인 시도가 너무 많아요. 2분 뒤에 다시 시도해 주세요."));
    }
}
