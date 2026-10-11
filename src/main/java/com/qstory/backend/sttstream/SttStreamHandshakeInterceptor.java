package com.qstory.backend.sttstream;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 연결 전에 출처를 보고(브라우저가 붙이는 Origin - 없으면 브라우저가 아니므로 표만 본다) ?ticket= 표를 쓴다(한 번만).
 * 출처가 낯설면 403, 표가 없거나 지났으면 401로 업그레이드를 거절한다 - 앱은 배치로 간다. 출처를 먼저 봐서 낯선
 * 페이지가 남의 표를 써 버리지 못하게 한다.
 */
public class SttStreamHandshakeInterceptor implements HandshakeInterceptor {

    static final String TICKET_ATTRIBUTE = "sttStreamTicket";

    private final SttStreamTicketService ticketService;
    private final CorsConfiguration origins = new CorsConfiguration();

    public SttStreamHandshakeInterceptor(SttStreamTicketService ticketService, List<String> allowedOrigins) {
        this.ticketService = ticketService;
        this.origins.setAllowedOrigins(allowedOrigins);
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin != null && origins.checkOrigin(origin) == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        String ticketId = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        return ticketService.redeem(ticketId)
                .map(ticket -> {
                    attributes.put(TICKET_ATTRIBUTE, ticket);
                    return true;
                })
                .orElseGet(() -> {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return false;
                });
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        // 할 일 없음
    }
}
