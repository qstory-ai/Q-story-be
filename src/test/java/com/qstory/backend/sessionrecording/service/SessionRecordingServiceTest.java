package com.qstory.backend.sessionrecording.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 072: 녹화 조각 - base64 검사, 조각·세션 상한(413), (베타 세션, seq) 멱등, 재생용 재인코딩. */
class SessionRecordingServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-08T10:00:00Z");
    private final UUID beta = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionRecordingService service = new SessionRecordingService(jdbc);

    @Test
    void gzipChunkStoresDecodedBytes() {
        byte[] raw = {31, -117, 8, 0, 1, 2, 3};
        SessionRecordingService.ParsedChunk chunk =
                SessionRecordingService.parse(body("gzip-base64", Base64.getEncoder().encodeToString(raw)), now);
        assertThat(chunk.data()).isEqualTo(raw);
        assertThat(chunk.seq()).isEqualTo(3);
        assertThat(chunk.eventCount()).isEqualTo(12);
        assertThat(SessionRecordingService.encodeForReplay("gzip-base64", chunk.data()))
                .isEqualTo(Base64.getEncoder().encodeToString(raw));
    }

    @Test
    void jsonChunkStoresUtf8Bytes() {
        String events = "[{\"type\":2,\"data\":\"아이\"}]";
        SessionRecordingService.ParsedChunk chunk = SessionRecordingService.parse(body("json", events), now);
        assertThat(chunk.data()).isEqualTo(events.getBytes(StandardCharsets.UTF_8));
        assertThat(SessionRecordingService.encodeForReplay("json", chunk.data())).isEqualTo(events);
    }

    @Test
    void invalidBase64Is400() {
        assertThatThrownBy(() -> SessionRecordingService.parse(body("gzip-base64", "not base64!!"), now))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.statusCode()).isEqualTo(400));
    }

    @Test
    void unknownEncodingIs400() {
        assertThatThrownBy(() -> SessionRecordingService.parse(body("brotli", "abc"), now))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void negativeSeqIs400() {
        ObjectNode body = body("json", "[]");
        body.put("seq", -1);
        assertThatThrownBy(() -> SessionRecordingService.parse(body, now))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.statusCode()).isEqualTo(400));
    }

    @Test
    void dataOverOneMillionCharsIs413() {
        String data = "A".repeat(SessionRecordingService.MAX_DATA_CHARS + 4);
        assertThatThrownBy(() -> SessionRecordingService.parse(body("gzip-base64", data), now))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
                    assertThat(e.statusCode()).isEqualTo(413);
                });
        assertThat(SessionRecordingService.parse(
                body("gzip-base64", "A".repeat(SessionRecordingService.MAX_DATA_CHARS)), now).data()).isNotEmpty();
    }

    @Test
    void sessionCapIs50MB() {
        long cap = SessionRecordingService.MAX_SESSION_BYTES;
        assertThat(SessionRecordingService.exceedsSessionCap(cap - 10, 10)).isFalse();
        assertThat(SessionRecordingService.exceedsSessionCap(cap - 10, 11)).isTrue();
    }

    @Test
    void storeOverSessionCapIs413() {
        when(jdbc.queryForObject(startsWith("select count(*)"), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(startsWith("select coalesce(sum(byte_size)"), eq(Long.class), any(Object[].class)))
                .thenReturn(SessionRecordingService.MAX_SESSION_BYTES);
        SessionRecordingService.ParsedChunk chunk = SessionRecordingService.parse(body("json", "[]"), now);
        assertThatThrownBy(() -> service.store(chunk, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.statusCode()).isEqualTo(413));
        verify(jdbc, never()).update(startsWith("insert into session_recording_chunk"), any(Object[].class));
    }

    @Test
    void duplicateSeqIsNoOpEvenWhenOverCap() {
        when(jdbc.queryForObject(startsWith("select count(*)"), eq(Integer.class), any(Object[].class))).thenReturn(1);
        SessionRecordingService.ParsedChunk chunk = SessionRecordingService.parse(body("json", "[]"), now);
        assertThat(service.store(chunk, null)).isFalse();
        verify(jdbc, never()).update(startsWith("insert into session_recording_chunk"), any(Object[].class));
    }

    @Test
    void newChunkIsInserted() {
        when(jdbc.queryForObject(startsWith("select count(*)"), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(startsWith("select coalesce(sum(byte_size)"), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.update(startsWith("insert into session_recording_chunk"), any(Object[].class))).thenReturn(1);
        assertThat(service.store(SessionRecordingService.parse(body("json", "[]"), now), UUID.randomUUID())).isTrue();
    }

    @Test
    void findRejectsMalformedCode() {
        assertThatThrownBy(() -> service.findSessions("ZZZ"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.statusCode()).isEqualTo(400));
    }

    private ObjectNode body(String encoding, String data) {
        ObjectNode body = json.createObjectNode();
        body.put("betaSessionId", beta.toString());
        body.put("seq", 3);
        body.put("startedAt", now.minusSeconds(10).toString());
        body.put("endedAt", now.toString());
        body.put("eventCount", 12);
        body.put("encoding", encoding);
        body.put("data", data);
        return body;
    }

    @Test
    void chunkPageStopsAtTheByteBudgetSoTheProxyResponseStaysSmall() {
        SessionRecordingService paged = new SessionRecordingService(jdbc) {
            @Override
            java.util.List<java.util.Map<String, Object>> listChunks(UUID id, int afterSeq, int limit) {
                return java.util.List.of(row(0, 2_000_000), row(1, 1_000_000), row(2, 100));
            }
        };
        java.util.Map<String, Object> page = paged.chunkPage(beta, -1);
        assertThat((java.util.List<?>) page.get("chunks")).hasSize(1);
        assertThat(page.get("nextAfterSeq")).isEqualTo(0);
        assertThat(((java.util.Map<?, ?>) ((java.util.List<?>) page.get("chunks")).get(0)).containsKey("byteSize")).isFalse();
    }

    @Test
    void lastChunkPageHasNoNextSeq() {
        SessionRecordingService paged = new SessionRecordingService(jdbc) {
            @Override
            java.util.List<java.util.Map<String, Object>> listChunks(UUID id, int afterSeq, int limit) {
                return java.util.List.of(row(5, 100), row(6, 100));
            }
        };
        java.util.Map<String, Object> page = paged.chunkPage(beta, 4);
        assertThat((java.util.List<?>) page.get("chunks")).hasSize(2);
        assertThat(page.get("nextAfterSeq")).isNull();
    }

    private static java.util.Map<String, Object> row(int seq, int byteSize) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("seq", seq);
        row.put("data", "x");
        row.put("byteSize", byteSize);
        return row;
    }
}
