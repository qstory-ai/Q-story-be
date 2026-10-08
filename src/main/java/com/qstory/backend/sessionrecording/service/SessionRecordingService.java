package com.qstory.backend.sessionrecording.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UT 화면 녹화(rrweb) 조각을 session_recording_chunk(072)에 저장하고, 팀 내부 재생용으로 다시 꺼낸다.
 *
 * <p>프런트가 일정 간격으로 자른 조각에 seq를 붙여 보낸다. 같은 (베타 세션, seq)는 한 번만 들어간다 - 네트워크가 끊겨
 * 다시 보내도 안전하다. 조각 하나는 문자열 1,000,000자, 베타 세션 하나는 합계 50MB까지 받는다. 상한을 넘으면 413을 주고
 * 프런트는 그 세션의 녹화를 멈춘다 - 오래 켜 둔 탭 하나가 DB를 채우지 않게 한다.
 *
 * <p>gzip-base64 조각은 디코드한 gzip 바이트를, json 조각은 UTF-8 바이트를 그대로 둔다. 서버는 압축을 풀지 않는다 -
 * 재생 화면(브라우저)이 푼다.
 */
@Service
public class SessionRecordingService {

    public static final int MAX_DATA_CHARS = 1_000_000;
    public static final long MAX_SESSION_BYTES = 50L * 1024 * 1024;
    static final String GZIP_BASE64 = "gzip-base64";
    static final String JSON = "json";
    private static final Set<String> ENCODINGS = Set.of(GZIP_BASE64, JSON);
    private static final Pattern CODE = Pattern.compile("^[0-9A-Fa-f]{6}$");

    public record ParsedChunk(
            UUID betaSessionId, UUID playSessionId, int seq, Instant startedAt, Instant endedAt, int eventCount,
            String encoding, byte[] data) {}

    private final JdbcTemplate jdbc;

    public SessionRecordingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 요청 본문을 검사해 저장할 조각으로 바꾼다. 형식이 틀리면 400, data가 너무 길면 413. */
    public static ParsedChunk parse(JsonNode body, Instant now) {
        if (body == null || !body.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        UUID betaSessionId = uuid(body.path("betaSessionId"));
        if (betaSessionId == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "betaSessionId가 필요해요.");
        }
        JsonNode seqNode = body.path("seq");
        if (!seqNode.canConvertToInt() || !seqNode.isIntegralNumber() || seqNode.asInt() < 0) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "seq는 0 이상의 정수여야 해요.");
        }
        String encoding = body.path("encoding").isTextual() ? body.path("encoding").asText().trim() : "";
        if (!ENCODINGS.contains(encoding)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "encoding은 gzip-base64 또는 json이어야 해요.");
        }
        JsonNode dataNode = body.path("data");
        if (!dataNode.isTextual() || dataNode.asText().isEmpty()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "data가 필요해요.");
        }
        String data = dataNode.asText();
        if (data.length() > MAX_DATA_CHARS) {
            throw ApiException.contractError(ErrorCode.PAYLOAD_TOO_LARGE, "녹화 조각이 너무 커요.", 413);
        }
        byte[] bytes;
        if (GZIP_BASE64.equals(encoding)) {
            try {
                bytes = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException malformed) {
                throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "data가 올바른 base64가 아니에요.");
            }
        } else {
            bytes = data.getBytes(StandardCharsets.UTF_8);
        }
        Instant startedAt = instantOr(body.path("startedAt"), now);
        Instant endedAt = instantOr(body.path("endedAt"), startedAt);
        if (endedAt.isBefore(startedAt)) endedAt = startedAt;
        JsonNode countNode = body.path("eventCount");
        int eventCount = countNode.canConvertToInt() && countNode.asInt() >= 0 ? countNode.asInt() : 0;
        return new ParsedChunk(betaSessionId, uuid(body.path("playSessionId")), seqNode.asInt(), startedAt, endedAt,
                eventCount, encoding, bytes);
    }

    /**
     * 조각을 저장한다. 이미 받은 seq면 아무것도 하지 않고 false(멱등 - 상한 검사도 건너뛴다).
     * 이 조각으로 세션 합계가 50MB를 넘으면 413.
     */
    @Transactional
    public boolean store(ParsedChunk chunk, UUID userId) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from session_recording_chunk where beta_session_id = ? and seq = ?",
                Integer.class, chunk.betaSessionId(), chunk.seq());
        if (existing != null && existing > 0) {
            return false;
        }
        Long total = jdbc.queryForObject(
                "select coalesce(sum(byte_size), 0) from session_recording_chunk where beta_session_id = ?",
                Long.class, chunk.betaSessionId());
        if (exceedsSessionCap(total == null ? 0 : total, chunk.data().length)) {
            throw ApiException.contractError(ErrorCode.PAYLOAD_TOO_LARGE, "이 세션의 녹화 용량을 다 썼어요.", 413);
        }
        int inserted = jdbc.update(
                "insert into session_recording_chunk (beta_session_id, seq, user_id, play_session_id, started_at, ended_at, "
                        + "event_count, encoding, data, byte_size, received_at) values (?,?,?,?,?,?,?,?,?,?,?) "
                        + "on conflict (beta_session_id, seq) do nothing",
                chunk.betaSessionId(), chunk.seq(), userId, chunk.playSessionId(), Timestamp.from(chunk.startedAt()),
                Timestamp.from(chunk.endedAt()), chunk.eventCount(), chunk.encoding(), chunk.data(), chunk.data().length,
                Timestamp.from(Instant.now()));
        return inserted > 0;
    }

    static boolean exceedsSessionCap(long storedBytes, long incomingBytes) {
        return storedBytes + incomingBytes > MAX_SESSION_BYTES;
    }

    /**
     * 관찰자가 적은 6자리 코드로 녹화가 있는 베타 세션을 찾는다. 코드는 베타 세션 코드이거나 회차 코드(play_session id 앞
     * 6자)다 - 회차 코드는 play_session.beta_session_id와 조각의 play_session_id 둘 다로 찾는다.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findSessions(String code) {
        if (code == null || !CODE.matcher(code.trim()).matches()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "코드는 6자리 영문·숫자(16진수)예요.");
        }
        String normalized = code.trim().toUpperCase();
        List<Map<String, Object>> sessions = jdbc.query(
                "with matched as ("
                        + " select c.beta_session_id from session_recording_chunk c"
                        + "  where upper(left(replace(c.beta_session_id::text, '-', ''), 6)) = ?"
                        + " union select c.beta_session_id from session_recording_chunk c"
                        + "  where c.play_session_id is not null and upper(left(replace(c.play_session_id::text, '-', ''), 6)) = ?"
                        + " union select s.beta_session_id from play_session s"
                        + "  where s.beta_session_id is not null and upper(left(replace(s.id::text, '-', ''), 6)) = ?"
                        + ") select c.beta_session_id, min(c.started_at) as started_at, max(c.ended_at) as ended_at,"
                        + " count(*) as chunk_count, sum(c.byte_size) as total_bytes,"
                        + " (array_agg(c.user_id order by c.seq desc) filter (where c.user_id is not null))[1] as user_id"
                        + " from matched m join session_recording_chunk c on c.beta_session_id = m.beta_session_id"
                        + " group by c.beta_session_id order by min(c.started_at) desc limit 50",
                (rs, rowNum) -> {
                    UUID betaSessionId = rs.getObject("beta_session_id", UUID.class);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("betaSessionId", betaSessionId.toString());
                    row.put("betaSessionCode", code(betaSessionId));
                    row.put("playSessionIds", List.of());
                    row.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
                    row.put("endedAt", rs.getTimestamp("ended_at").toInstant().toString());
                    row.put("chunkCount", rs.getInt("chunk_count"));
                    row.put("totalBytes", rs.getLong("total_bytes"));
                    UUID userId = rs.getObject("user_id", UUID.class);
                    row.put("userId", userId == null ? null : userId.toString());
                    return row;
                },
                normalized, normalized, normalized);
        for (Map<String, Object> session : sessions) {
            UUID betaSessionId = UUID.fromString((String) session.get("betaSessionId"));
            List<String> playSessionIds = jdbc.queryForList(
                    "select distinct id::text from ("
                            + " select play_session_id as id from session_recording_chunk"
                            + "  where beta_session_id = ? and play_session_id is not null"
                            + " union select id from play_session where beta_session_id = ?) ids order by 1",
                    String.class, betaSessionId, betaSessionId);
            session.put("playSessionIds", playSessionIds);
        }
        return sessions;
    }

    /** 재생용 - 한 베타 세션의 조각 전부를 seq 순으로. gzip 조각은 base64로 다시 싸고, json 조각은 문자열 그대로. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listChunks(UUID betaSessionId) {
        return jdbc.query(
                "select seq, started_at, ended_at, event_count, encoding, data from session_recording_chunk "
                        + "where beta_session_id = ? order by seq",
                (rs, rowNum) -> {
                    String encoding = rs.getString("encoding");
                    byte[] data = rs.getBytes("data");
                    Map<String, Object> chunk = new LinkedHashMap<>();
                    chunk.put("seq", rs.getInt("seq"));
                    chunk.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
                    chunk.put("endedAt", rs.getTimestamp("ended_at").toInstant().toString());
                    chunk.put("eventCount", rs.getInt("event_count"));
                    chunk.put("encoding", encoding);
                    chunk.put("data", encodeForReplay(encoding, data));
                    return chunk;
                },
                betaSessionId);
    }

    static String encodeForReplay(String encoding, byte[] data) {
        return GZIP_BASE64.equals(encoding)
                ? Base64.getEncoder().encodeToString(data)
                : new String(data, StandardCharsets.UTF_8);
    }

    /** 보관 기간(90일)이 지난 조각을 지운다(SessionRecordingRetentionScheduler). */
    @Transactional
    public int deleteReceivedBefore(Instant cutoff) {
        return jdbc.update("delete from session_recording_chunk where received_at < ?", Timestamp.from(cutoff));
    }

    static String code(UUID id) {
        return id.toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) return null;
        try {
            return UUID.fromString(node.asText().trim());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static Instant instantOr(JsonNode node, Instant fallback) {
        if (node == null || !node.isTextual()) return fallback;
        try {
            return Instant.parse(node.asText().trim());
        } catch (DateTimeParseException malformed) {
            return fallback;
        }
    }
}
