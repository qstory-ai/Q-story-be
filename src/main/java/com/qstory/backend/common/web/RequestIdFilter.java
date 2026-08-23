package com.qstory.backend.common.web;

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
 * Stamps x-qstory-request-id on every response, same as before. CORS/origin-checking used to live
 * here too (as CorsRequestFilter) but has moved to Spring Security's standard CORS config (see
 * com.qstory.backend.identity.config.SecurityConfig) - this filter now only does request-id
 * tracing, kept separate from GlobalExceptionHandler's own request-id fallback so successful
 * responses get a stable id too, not just error responses.
 */
@Component
@Order(1)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_ATTRIBUTE = "qstoryRequestId";

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
        chain.doFilter(request, response);
    }
}
