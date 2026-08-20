package com.qstory.backend.betaevents;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.enums.EventSource;
import com.qstory.backend.common.error.EdgeErrorCode;
import com.qstory.backend.common.error.EdgeException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Java port of beta-events/index.ts's request validation and metadata allow-listing. */
@Component
public class BetaEventValidator {

    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("event_id", "session_id", "event_name", "source", "occurred_at", "metadata", "schema_version");

    private static final Set<String> COMMON_METADATA_KEYS = Set.of(
            "app_version", "story_version", "viewport_class", "browser_family", "platform_class",
            "traffic_type", "landing_release", "utm_source", "utm_medium", "utm_campaign", "utm_content");

    private static final Set<String> LONG_TEXT_KEYS = Set.of("question_text", "question_intent");

    private static final Map<EventName, Set<String>> METADATA_KEYS = buildMetadataKeys();

    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?:\\+?82[- ]?)?0?1[016789][- ]?\\d{3,4}[- ]?\\d{4}");
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{6,}");
    private static final Pattern NAME_PATTERN = Pattern.compile("(?:내|제)\\s*이름은\\s*[\\p{L}]{1,20}");

    public record ParsedEvent(
            UUID eventId, UUID sessionId, EventName eventName, EventSource source, Instant occurredAt,
            Map<String, Object> metadata) {}

    public ParsedEvent parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new EdgeException(EdgeErrorCode.INVALID_PAYLOAD);
        }
        Iterator<String> keys = body.fieldNames();
        while (keys.hasNext()) {
            if (!TOP_LEVEL_KEYS.contains(keys.next())) {
                throw new EdgeException(EdgeErrorCode.UNSUPPORTED_FIELD);
            }
        }

        UUID eventId = parseUuid(body.path("event_id"));
        UUID sessionId = parseUuid(body.path("session_id"));
        EventName eventName = parseEnum(body.path("event_name").asText(""), EventName.class);
        EventSource source = parseEnum(body.path("source").asText(""), EventSource.class);
        if (eventName == null || source == null || source != eventName.requiredSource()) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        if (body.path("schema_version").asInt(-1) != 1) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        Instant occurredAt = parseOccurredAt(body.path("occurred_at").asText(""));
        Map<String, Object> metadata = parseMetadata(body.path("metadata"), eventName);

        return new ParsedEvent(eventId, sessionId, eventName, source, occurredAt, metadata);
    }

    private Map<String, Object> parseMetadata(JsonNode node, EventName eventName) {
        if (node == null || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        Set<String> allowedKeys = new LinkedHashSet<>(COMMON_METADATA_KEYS);
        allowedKeys.addAll(METADATA_KEYS.getOrDefault(eventName, Set.of()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        Iterator<String> keys = node.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowedKeys.contains(key)) {
                throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
            }
            JsonNode value = node.get(key);
            boolean isLongText = LONG_TEXT_KEYS.contains(key);
            metadata.put(key, validatedValue(value, isLongText));
        }
        return metadata;
    }

    private Object validatedValue(JsonNode value, boolean isLongText) {
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            double number = value.asDouble();
            if (!Double.isFinite(number) || number < 0) {
                throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
            }
            return value.isIntegralNumber() ? (Object) value.asLong() : (Object) number;
        }
        if (value.isTextual()) {
            String text = value.asText();
            int max = isLongText ? 240 : 80;
            if (text.isEmpty() || text.length() > max) {
                throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
            }
            return isLongText ? sanitizeQuestionText(text) : text;
        }
        throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
    }

    public String sanitizeQuestionText(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = EMAIL.matcher(normalized).replaceAll("[이메일]");
        normalized = URL.matcher(normalized).replaceAll("[링크]");
        normalized = PHONE.matcher(normalized).replaceAll("[연락처]");
        normalized = DIGIT_RUN.matcher(normalized).replaceAll("[숫자]");
        normalized = NAME_PATTERN.matcher(normalized).replaceAll("내 이름은 [이름]");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }

    private UUID parseUuid(JsonNode node) {
        try {
            return UUID.fromString(node.asText(""));
        } catch (IllegalArgumentException malformed) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
    }

    private <T extends Enum<T>> T parseEnum(String value, Class<T> type) {
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private Instant parseOccurredAt(String value) {
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(value);
        } catch (Exception malformed) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        Instant now = Instant.now();
        if (occurredAt.isBefore(now.minus(Duration.ofDays(7))) || occurredAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        return occurredAt;
    }

    private static Map<EventName, Set<String>> buildMetadataKeys() {
        Map<EventName, Set<String>> keys = new LinkedHashMap<>();
        keys.put(EventName.LANDING_VIEW, Set.of("page", "entry"));
        keys.put(EventName.LANDING_CTA_CLICK, Set.of("cta_location"));
        keys.put(EventName.STORY_STARTED, Set.of("resume"));
        keys.put(EventName.SCENE_REACHED, Set.of("scene_id"));
        keys.put(EventName.QUESTION_INVITE_SHOWN, Set.of("anchor_id", "scene_id"));
        keys.put(EventName.QUESTION_SKIPPED, Set.of("anchor_id", "scene_id", "skip_reason"));
        keys.put(EventName.QUESTION_STARTED, Set.of("anchor_id", "input_mode"));
        keys.put(EventName.CHOICE_SELECTED, Set.of("anchor_id", "scene_id", "option_id", "family_id"));
        keys.put(EventName.QUESTION_RESULT, Set.of(
                "anchor_id", "route", "result", "failure_stage", "failure_code", "retryable", "attempt_count",
                "latency_ms", "stt_ms", "route_ms", "first_audio_ms", "branch_playback_ms", "stt_attempt_count",
                "first_pass_accepted", "transcript_corrected", "switched_input", "coverage_status",
                "uncovered_intent", "question_text", "question_intent", "family_id", "model_id", "prompt_version"));
        keys.put(EventName.PLAYBACK_ISSUE, Set.of(
                "issue_type", "scene_id", "anchor_id", "family_id", "clip_id", "audio_state", "audio_source",
                "runtime_status", "failure_code", "retryable"));
        keys.put(EventName.EXPLICIT_EXIT, Set.of(
                "reason_code", "scene_id", "anchor_id", "family_id", "clip_id", "audio_state", "audio_source",
                "runtime_status", "failure_code"));
        keys.put(EventName.STORY_COMPLETED, Set.of("duration_seconds", "question_count", "changed_scene_count"));
        keys.put(EventName.PARENT_REPORT_OPENED, Set.of());
        keys.put(EventName.SURVEY_OPENED, Set.of());
        return Map.copyOf(keys);
    }
}
