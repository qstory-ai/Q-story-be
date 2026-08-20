package com.qstory.backend.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.Failure;
import com.qstory.backend.common.error.FailureBody;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Replicates server.mjs's setCors()/OPTIONS handling and the x-qstory-request-id header that
 * is stamped on every response. Runs before the dispatcher so a disallowed Origin never reaches
 * a controller, exactly like the Node request handler's synchronous CORS check.
 */
@Component
@Order(1)
public class CorsRequestFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_ATTRIBUTE = "qstoryRequestId";

    private final AppProperties config;
    private final ObjectMapper objectMapper;

    public CorsRequestFilter(AppProperties config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** Swagger UI's own same-origin doc requests aren't part of the client-facing CORS surface this filter guards. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("x-qstory-request-id", requestId);

        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            if (!config.allowedOrigins().contains(origin)) {
                writeOriginNotAllowed(response);
                return;
            }
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader(
                    "Access-Control-Allow-Headers",
                    "content-type, x-qstory-story-id, x-qstory-scene-id, x-qstory-anchor-id, x-qstory-question-round");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.setHeader("Access-Control-Max-Age", "600");
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        chain.doFilter(request, response);
    }

    private void writeOriginNotAllowed(HttpServletResponse response) throws IOException {
        Failure failure = new Failure(ErrorCode.ORIGIN_NOT_ALLOWED.name(), "upload", false, "Origin is not allowed");
        response.setStatus(ErrorCode.ORIGIN_NOT_ALLOWED.defaultStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), FailureBody.of(failure));
    }
}
