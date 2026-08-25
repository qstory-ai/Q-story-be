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
 * 예전과 동일하게 모든 응답에 x-qstory-request-id를 찍는다. CORS/origin 검사도 예전에는
 * (CorsRequestFilter라는 이름으로) 여기 함께 있었지만 지금은 Spring Security의 표준 CORS
 * 설정으로 옮겨갔다 (com.qstory.backend.identity.config.SecurityConfig 참고) - 이제 이 필터는
 * request-id 추적만 담당하며, GlobalExceptionHandler 자체의 request-id 폴백과는 별개로
 * 유지된다. 그래야 에러 응답뿐 아니라 성공 응답에도 안정적인 id가 부여되기 때문이다.
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
