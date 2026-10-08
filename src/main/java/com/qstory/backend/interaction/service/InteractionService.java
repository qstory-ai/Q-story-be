package com.qstory.backend.interaction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화면 상호작용(화면 진입·이탈, 탭, 스크롤, 머뭇거림)을 묶음으로 받아 interaction_event(072)에 남긴다. UT에서 아이·보호자가
 * 어디서 머뭇거리고 무엇을 눌렀는지를 회차 기록과 같은 베타 세션으로 이어 보기 위한 기록이다.
 *
 * <p>로그인 없이 받는 공개 수집이라 모양을 좁게 받는다: 정해진 kind만 받고(모르는 kind는 프런트 버그라 400으로 드러낸다),
 * 글자는 열 크기에 맞춰 자르고, 좌표·스크롤 깊이는 0..1로 자른다. 기기 시계가 크게 어긋난 이벤트(7일 전보다 오래되었거나
 * 5분 넘게 미래)는 분석을 흐리므로 그 이벤트만 버린다.
 */
@Service
public class InteractionService {

    public static final int MAX_EVENTS_PER_REQUEST = 200;
    static final Set<String> KINDS = Set.of("SCREEN_VIEW", "SCREEN_LEAVE", "TAP", "SCROLL", "HESITATION");
    static final Duration MAX_PAST = Duration.ofDays(7);
    static final Duration MAX_FUTURE = Duration.ofMinutes(5);
    /** metadata는 작은 보조 값만 - 직렬화해서 이보다 길면 버린다(이벤트는 남긴다). */
    static final int MAX_METADATA_CHARS = 2_000;
    private static final int MAX_VIEWPORT = 100_000;

    public record ParsedEvent(
            String kind, Instant occurredAt, String screen, String target, String targetRole, Float x, Float y,
            Integer viewportW, Integer viewportH, Float scrollDepth, Integer durationMs, String metadataJson) {}

    public record ParsedBatch(UUID betaSessionId, UUID playSessionId, List<ParsedEvent> events) {}

    private final JdbcTemplate jdbc;

    public InteractionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 요청 본문을 검사해 저장할 이벤트만 남긴다. 형식이 틀리면 400, 시각이 어긋난 이벤트는 조용히 뺀다. */
    public static ParsedBatch parse(JsonNode body, Instant now) {
        if (body == null || !body.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        UUID betaSessionId = uuid(body.path("betaSessionId"));
        if (betaSessionId == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "betaSessionId가 필요해요.");
        }
        JsonNode events = body.path("events");
        if (!events.isArray()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "events가 필요해요.");
        }
        if (events.size() > MAX_EVENTS_PER_REQUEST) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "상호작용은 한 번에 200개까지 보낼 수 있어요.");
        }
        List<ParsedEvent> parsed = new ArrayList<>();
        for (JsonNode event : events) {
            ParsedEvent one = parseEvent(event, now);
            if (one != null) parsed.add(one);
        }
        return new ParsedBatch(betaSessionId, uuid(body.path("playSessionId")), parsed);
    }

    private static ParsedEvent parseEvent(JsonNode event, Instant now) {
        if (event == null || !event.isObject()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "상호작용 형식이 올바르지 않아요.");
        }
        String kind = event.path("kind").isTextual() ? event.path("kind").asText().trim().toUpperCase() : "";
        if (!KINDS.contains(kind)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "알 수 없는 상호작용 종류예요.");
        }
        Instant occurredAt = instant(event.path("occurredAt"));
        if (occurredAt == null || occurredAt.isBefore(now.minus(MAX_PAST)) || occurredAt.isAfter(now.plus(MAX_FUTURE))) {
            return null;
        }
        JsonNode metadata = event.path("metadata");
        String metadataJson = null;
        if (metadata.isObject() && !metadata.isEmpty()) {
            String serialized = metadata.toString();
            metadataJson = serialized.length() <= MAX_METADATA_CHARS ? serialized : null;
        }
        return new ParsedEvent(
                kind, occurredAt,
                text(event.path("screen"), 120), text(event.path("target"), 120), text(event.path("targetRole"), 32),
                unit(event.path("x")), unit(event.path("y")),
                positive(event.path("viewportW"), MAX_VIEWPORT), positive(event.path("viewportH"), MAX_VIEWPORT),
                unit(event.path("scrollDepth")), nonNegative(event.path("durationMs")), metadataJson);
    }

    /** 검사된 묶음을 한 번에 넣고, 넣은 개수를 돌려준다. */
    @Transactional
    public int record(ParsedBatch batch, UUID userId) {
        if (batch.events().isEmpty()) return 0;
        Timestamp receivedAt = Timestamp.from(Instant.now());
        List<Object[]> rows = new ArrayList<>(batch.events().size());
        for (ParsedEvent e : batch.events()) {
            rows.add(new Object[] {
                    batch.betaSessionId(), userId, batch.playSessionId(), Timestamp.from(e.occurredAt()), receivedAt,
                    e.kind(), e.screen(), e.target(), e.targetRole(), e.x(), e.y(), e.viewportW(), e.viewportH(),
                    e.scrollDepth(), e.durationMs(), e.metadataJson()});
        }
        jdbc.batchUpdate(
                "insert into interaction_event (beta_session_id, user_id, play_session_id, occurred_at, received_at, kind, "
                        + "screen, target, target_role, x, y, viewport_w, viewport_h, scroll_depth, duration_ms, metadata) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))",
                rows);
        return rows.size();
    }

    /** 보관 기간(90일)이 지난 상호작용을 지운다(InteractionRetentionScheduler). */
    @Transactional
    public int deleteReceivedBefore(Instant cutoff) {
        return jdbc.update("delete from interaction_event where received_at < ?", Timestamp.from(cutoff));
    }

    static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) return null;
        try {
            return UUID.fromString(node.asText().trim());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    static String text(JsonNode node, int max) {
        if (node == null || !node.isTextual()) return null;
        String value = node.asText().trim();
        if (value.isEmpty()) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** 화면 크기 대비 비율 - 0..1 밖이면 끝으로 붙인다. */
    static Float unit(JsonNode node) {
        if (node == null || !node.isNumber()) return null;
        double value = node.asDouble();
        if (Double.isNaN(value)) return null;
        return (float) Math.max(0d, Math.min(1d, value));
    }

    private static Integer positive(JsonNode node, int max) {
        if (node == null || !node.isNumber()) return null;
        long value = node.asLong();
        return value > 0 && value <= max ? (int) value : null;
    }

    private static Integer nonNegative(JsonNode node) {
        if (node == null || !node.isNumber()) return null;
        long value = node.asLong();
        if (value < 0) return null;
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private static Instant instant(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        try {
            return Instant.parse(node.asText().trim());
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }
}
