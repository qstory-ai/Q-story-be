package com.qstory.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 보안 점검 M5 - 로그인 없이 부를 수 있는 JSON 엔드포인트의 요청 크기 상한. */
class JsonBodyLimitFilterTest {

    private final JsonBodyLimitFilter filter = new JsonBodyLimitFilter(new ObjectMapper());

    private static MockHttpServletRequest post(String path, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    void limitsApplyToListedPathsOnly() {
        assertThat(JsonBodyLimitFilter.limitFor(post("/v1/recording-consents", 1))).isEqualTo(JsonBodyLimitFilter.SMALL);
        assertThat(JsonBodyLimitFilter.limitFor(post("/v1/play-sessions/" + java.util.UUID.randomUUID() + "/turns", 1)))
                .isEqualTo(JsonBodyLimitFilter.PLAY_TURNS);
        assertThat(JsonBodyLimitFilter.limitFor(post("/v1/launch-notifications", 1))).isEqualTo(JsonBodyLimitFilter.SMALL);
        assertThat(JsonBodyLimitFilter.limitFor(post("/v1/completion-surveys", 1))).isEqualTo(JsonBodyLimitFilter.SMALL);
        assertThat(JsonBodyLimitFilter.limitFor(post("/v1/stories", 1))).isEqualTo(-1);
        assertThat(JsonBodyLimitFilter.limitFor(new MockHttpServletRequest("GET", "/v1/recording-consents"))).isEqualTo(-1);
    }

    @Test
    void declaredOversizeBodyIs413WithoutReachingTheController() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/v1/recording-consents", (int) JsonBodyLimitFilter.SMALL + 1), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void bodyWithinLimitPasses() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(post("/v1/recording-consents", 100), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    /** 길이를 밝히지 않은(chunked) 요청은 읽는 도중 상한을 넘는 순간 413으로 끊는다. */
    @Test
    void undeclaredOversizeBodyStopsWhileReading() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/completion-surveys") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[(int) JsonBodyLimitFilter.SMALL + 10]);
        AtomicReference<ServletRequest> passed = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(req));

        InputStream body = ((HttpServletRequest) passed.get()).getInputStream();
        assertThatThrownBy(body::readAllBytes)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.statusCode()).isEqualTo(413));
    }
}
