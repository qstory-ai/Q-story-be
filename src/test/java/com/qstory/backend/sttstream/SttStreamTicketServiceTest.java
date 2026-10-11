package com.qstory.backend.sttstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.config.SttStreamProperties;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SttStreamTicketServiceTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-11T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();

    private SttStreamTicketService service(boolean enabled, int maxConcurrent, int sessionLimit, int globalLimit) {
        return new SttStreamTicketService(
                new SttStreamProperties(enabled, maxConcurrent, 35_000, 30_000, sessionLimit, globalLimit),
                new SecureTokenGenerator(), clock);
    }

    private static ConversationAttribution session(UUID sessionId) {
        return ConversationAttribution.of(sessionId, null, null, null, ConversationInputMode.VOICE, null);
    }

    private static SttStreamTicket issue(SttStreamTicketService service, UUID sessionId) {
        return service.issue("story", "scene", List.of("그레텔"), session(sessionId));
    }

    @Test
    void ticketIsSingleUse() {
        SttStreamTicketService service = service(true, 2, 10, 10);
        SttStreamTicket ticket = issue(service, UUID.randomUUID());

        assertEquals("story", service.redeem(ticket.id()).orElseThrow().storyId());
        assertTrue(service.redeem(ticket.id()).isEmpty());
        assertTrue(service.redeem("unknown").isEmpty());
        assertTrue(service.redeem(null).isEmpty());
    }

    @Test
    void expiredTicketIsRejected() {
        SttStreamTicketService service = service(true, 2, 10, 10);
        SttStreamTicket ticket = issue(service, UUID.randomUUID());
        clock.now = clock.now.plus(Duration.ofSeconds(31));

        assertTrue(service.redeem(ticket.id()).isEmpty());
    }

    @Test
    void disabledFlagRefusesTicketsSoTheAppUsesBatch() {
        SttStreamTicketService service = service(false, 2, 10, 10);
        ApiException error = assertThrows(ApiException.class, () -> issue(service, UUID.randomUUID()));
        assertEquals(ErrorCode.STT_STREAM_UNAVAILABLE, error.code());
        assertEquals(503, error.statusCode());
    }

    @Test
    void noFreeChannelRefusesTickets() {
        SttStreamTicketService service = service(true, 1, 10, 10);
        assertTrue(service.tryAcquireChannel());
        assertFalse(service.tryAcquireChannel());

        ApiException error = assertThrows(ApiException.class, () -> issue(service, UUID.randomUUID()));
        assertEquals(ErrorCode.STT_STREAM_UNAVAILABLE, error.code());

        service.releaseChannel();
        issue(service, UUID.randomUUID());
    }

    @Test
    void perSessionLimitResetsAfterTheWindow() {
        SttStreamTicketService service = service(true, 2, 2, 100);
        UUID sessionId = UUID.randomUUID();
        issue(service, sessionId);
        issue(service, sessionId);

        ApiException error = assertThrows(ApiException.class, () -> issue(service, sessionId));
        assertEquals(ErrorCode.RATE_LIMITED, error.code());
        // 다른 대화는 따로 센다.
        issue(service, UUID.randomUUID());

        clock.now = clock.now.plus(SttStreamTicketService.SESSION_WINDOW).plusSeconds(1);
        issue(service, sessionId);
    }

    @Test
    void globalLimitCapsTicketsAcrossSessions() {
        SttStreamTicketService service = service(true, 2, 100, 3);
        for (int i = 0; i < 3; i++) {
            issue(service, UUID.randomUUID());
        }
        assertThrows(ApiException.class, () -> issue(service, UUID.randomUUID()));

        clock.now = clock.now.plus(SttStreamTicketService.GLOBAL_WINDOW).plusSeconds(1);
        issue(service, UUID.randomUUID());
    }
}
