package com.qstory.backend.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** content-type/cache-control을 고정 값으로 주고 JSON을 직접 쓴다 - 프레임워크의 협상(negotiation)을 거치지 않는다. */
public final class HttpJsonWriter {

    private HttpJsonWriter() {}

    public static void writeJson(
            HttpServletResponse response, ObjectMapper objectMapper, int statusCode, Object value)
            throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(value);
        response.setStatus(statusCode);
        response.setContentType("application/json; charset=utf-8");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }
}
