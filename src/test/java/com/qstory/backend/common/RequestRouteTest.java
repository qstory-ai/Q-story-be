package com.qstory.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.qstory.backend.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestRouteTest {

    @Test
    void filterKeepsRequestIdAndPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/stories/HG/content");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader("x-qstory-request-id")).isNotBlank();
        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isNotNull();
    }
}
