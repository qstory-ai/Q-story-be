package com.qstory.backend.interaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 072: 공개 수집이라 모양을 좁게 받는다 - 모르는 kind는 400, 200개 초과는 400, 좌표는 0..1, 시각이 어긋난 이벤트는 버린다. */
class InteractionServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-08T10:00:00Z");
    private final UUID beta = UUID.randomUUID();

    @Test
    void parsesAndClampsOneTap() {
        ObjectNode body = body(1);
        ObjectNode tap = (ObjectNode) body.withArray("events").get(0);
        tap.put("x", 1.7).put("y", -0.2).put("scrollDepth", 0.5).put("viewportW", 390).put("viewportH", -1)
                .put("target", "x".repeat(300)).put("durationMs", -5);
        tap.putObject("metadata").put("sceneId", "s1");
        body.put("playSessionId", UUID.randomUUID().toString());

        InteractionService.ParsedBatch batch = InteractionService.parse(body, now);

        assertThat(batch.betaSessionId()).isEqualTo(beta);
        assertThat(batch.playSessionId()).isNotNull();
        InteractionService.ParsedEvent event = batch.events().get(0);
        assertThat(event.kind()).isEqualTo("TAP");
        assertThat(event.x()).isEqualTo(1f);
        assertThat(event.y()).isEqualTo(0f);
        assertThat(event.scrollDepth()).isEqualTo(0.5f);
        assertThat(event.viewportW()).isEqualTo(390);
        assertThat(event.viewportH()).isNull();
        assertThat(event.target()).hasSize(120);
        assertThat(event.durationMs()).isNull();
        assertThat(event.metadataJson()).isEqualTo("{\"sceneId\":\"s1\"}");
    }

    @Test
    void unknownKindIs400() {
        ObjectNode body = body(1);
        ((ObjectNode) body.withArray("events").get(0)).put("kind", "KEYSTROKE");
        assertValidationFailed(body);
    }

    @Test
    void moreThan200EventsIs400() {
        assertThat(InteractionService.parse(body(200), now).events()).hasSize(200);
        assertValidationFailed(body(201));
    }

    @Test
    void missingBetaSessionIs400() {
        ObjectNode body = body(1);
        body.put("betaSessionId", "not-a-uuid");
        assertValidationFailed(body);
    }

    @Test
    void dropsEventsOutsideTheClockWindow() {
        ObjectNode body = body(4);
        ArrayNode events = body.withArray("events");
        ((ObjectNode) events.get(0)).put("occurredAt", now.minus(Duration.ofDays(8)).toString());
        ((ObjectNode) events.get(1)).put("occurredAt", now.plus(Duration.ofMinutes(10)).toString());
        ((ObjectNode) events.get(2)).put("occurredAt", "yesterday");
        ((ObjectNode) events.get(3)).put("occurredAt", now.minus(Duration.ofDays(6)).toString());
        assertThat(InteractionService.parse(body, now).events()).hasSize(1);
    }

    @Test
    void oversizedMetadataIsDroppedButEventKept() {
        ObjectNode body = body(1);
        ((ObjectNode) body.withArray("events").get(0)).putObject("metadata").put("blob", "y".repeat(3_000));
        InteractionService.ParsedEvent event = InteractionService.parse(body, now).events().get(0);
        assertThat(event.metadataJson()).isNull();
    }

    private void assertValidationFailed(ObjectNode body) {
        assertThatThrownBy(() -> InteractionService.parse(body, now))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.statusCode()).isEqualTo(400);
                });
    }

    private ObjectNode body(int count) {
        ObjectNode body = json.createObjectNode();
        body.put("betaSessionId", beta.toString());
        ArrayNode events = body.putArray("events");
        for (int i = 0; i < count; i++) {
            events.addObject().put("kind", "tap").put("occurredAt", now.minusSeconds(i).toString()).put("screen", "/play");
        }
        return body;
    }
}
