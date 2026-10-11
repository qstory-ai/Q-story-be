package com.qstory.backend.sttstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.config.SttStreamProperties;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SttStreamHandshakeInterceptorTest {

    private final SttStreamTicketService tickets = new SttStreamTicketService(
            new SttStreamProperties(true, 2, 35_000, 30_000, 10, 10), new SecureTokenGenerator());

    private final SttStreamHandshakeInterceptor interceptor = new SttStreamHandshakeInterceptor(
            tickets, List.of("https://demo.qstory.ai.kr", "https://app.qstory.ai.kr", "capacitor://app.qstory.ai.kr"));

    private String ticket() {
        return tickets.issue("story", "scene", List.of(),
                ConversationAttribution.of(UUID.randomUUID(), null, null, null, ConversationInputMode.VOICE, null)).id();
    }

    private record Attempt(boolean accepted, int status, Map<String, Object> attributes) {}

    private Attempt handshake(String origin, String ticket) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/stt/stream");
        if (ticket != null) {
            request.setQueryString("ticket=" + ticket);
        }
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletServerHttpResponse serverResponse = new ServletServerHttpResponse(response);
        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request), serverResponse, null, attributes);
        serverResponse.close();
        return new Attempt(accepted, response.getStatus(), attributes);
    }

    @Test
    void webAndAppOriginsWithAValidTicketAreAccepted() {
        for (String origin : List.of("https://demo.qstory.ai.kr", "https://app.qstory.ai.kr", "capacitor://app.qstory.ai.kr")) {
            Attempt attempt = handshake(origin, ticket());
            assertTrue(attempt.accepted(), origin);
            assertTrue(attempt.attributes().get(SttStreamHandshakeInterceptor.TICKET_ATTRIBUTE) instanceof SttStreamTicket);
        }
    }

    @Test
    void foreignOriginIsRejectedWithoutSpendingTheTicket() {
        String ticket = ticket();
        Attempt attempt = handshake("https://evil.example", ticket);
        assertFalse(attempt.accepted());
        assertEquals(HttpStatus.FORBIDDEN.value(), attempt.status());

        assertTrue(handshake("https://demo.qstory.ai.kr", ticket).accepted());
    }

    @Test
    void missingOrReusedTicketIsUnauthorized() {
        assertEquals(401, handshake("https://demo.qstory.ai.kr", null).status());
        String ticket = ticket();
        assertTrue(handshake(null, ticket).accepted());
        Attempt reuse = handshake(null, ticket);
        assertFalse(reuse.accepted());
        assertEquals(401, reuse.status());
    }

    @Test
    void streamOriginsAlwaysIncludeWebAndAppOriginsEvenWhenEnvOverridesAllowedOrigins() {
        AppProperties config = mock(AppProperties.class);
        when(config.allowedOrigins()).thenReturn(List.of("https://dev.qstory.ai.kr"));
        when(config.nativeOrigins()).thenReturn(List.of("https://app.qstory.ai.kr", "capacitor://app.qstory.ai.kr"));

        List<String> origins = SttStreamWebSocketConfig.streamOrigins(config);

        assertTrue(origins.contains("https://demo.qstory.ai.kr"));
        assertTrue(origins.contains("https://app.qstory.ai.kr"));
        assertTrue(origins.contains("capacitor://app.qstory.ai.kr"));
        assertEquals(origins.stream().distinct().count(), origins.size());
    }
}
