package com.qstory.backend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 성공·에러 응답 모두에 x-qstory-request-id를 찍고, 같은 id를 요청 속성에 남겨
 * GlobalExceptionHandler의 에러 로그가 그 id로 찍히게 한다.
 *
 * 요청이 끝나면 한 줄 남긴다(http.request) - Grafana가 이 줄로 응답 시간·5xx 비율·트래픽을 본다
 * (ops/grafana-alerting). route는 실제 주소가 아니라 매핑된 패턴(/v1/stories/{storyId}/content)이라
 * 값 종류가 적다. 헬스 체크는 1분마다 오므로 남기지 않는다.
 */
@Component
@Order(1)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "qstoryRequestId";

    private static final Logger log = LoggerFactory.getLogger("http.request");

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
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.getRequestURI().startsWith("/health")) {
                log.info(
                        "http.request method={} route={} status={} duration_ms={} request_id={}",
                        request.getMethod(),
                        routeOf(request),
                        response.getStatus(),
                        (System.nanoTime() - startedAt) / 1_000_000,
                        requestId);
            }
        }
    }

    /** 매핑된 경로 패턴. 매핑이 없으면(404, 정적 파일) 실제 주소 대신 "unmatched"로 묶는다. */
    static String routeOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route ? route : "unmatched";
    }
}
