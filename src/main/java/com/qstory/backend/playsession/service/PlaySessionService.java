package com.qstory.backend.playsession.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차(play_session)와 그 안의 대화 한 줄 한 줄(play_turn)을 저장·조회한다(Q-39, 069). 부모 리포트가 아이 말
 * 원문과 앞뒤 대화를 그대로 보여 주기 위한 기록이다 - AI가 다시 쓴 문장이 아니라 화면에서 실제로 오간 말이다.
 *
 * <p>같은 (session, seq)는 한 번만 들어간다(재전송 멱등). 회차의 주인은 처음 보낸 사용자로 고정되고, 다른
 * 사용자가 같은 session id로 보내면 없는 회차처럼 404를 준다. 아이·학생·수업 id는 호출자 것일 때만 남긴다.
 *
 * <p>랜딩의 데모처럼 로그인 전 플레이도 남긴다(072) - 이때 주인은 베타 세션 id(betaSessionId)다. 같은 베타 세션으로
 * 익명 회차를 이어 쓰던 사람이 로그인한 채 보내면 그 계정이 회차를 가져간다(claim). 한 번 계정에 붙은 회차는 익명으로는
 * 더 쓸 수 없다 - 베타 세션 id만 아는 사람이 아이 기록이 붙은 회차에 줄을 끼워 넣지 못하게 한다.
 */
@Service
public class PlaySessionService {

    static final int MAX_TURNS_PER_REQUEST = 50;
    static final int MAX_TEXT = 500;

    private static final Set<String> ROLES = Set.of("CHILD", "CHARACTER", "SYSTEM");
    private static final Set<String> SPEAKERS = Set.of("UNVERIFIED", "GUARDIAN_PROXY", "TEACHER_RELAY");
    private static final Set<String> ENTRY_MODES = Set.of("SPONTANEOUS", "INVITE", "HELP");
    private static final Set<String> INPUT_MODES = Set.of("VOICE", "TEXT");
    private static final Set<String> EVENTS = Set.of(
            "ACTION_CONFIRMED", "ACTION_DECLINED", "INVITE_SKIPPED", "INVITE_CLOSED", "REPLY_FAILED", "STT_FAILED");
    private static final Set<String> PLAY_SETTINGS = Set.of("HOME", "INDIVIDUAL", "SMALL_GROUP", "WHOLE_CLASS");

    private final JdbcTemplate jdbc;

    public PlaySessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 대화 줄을 받아 저장하고, 지금까지 저장된 가장 큰 seq를 돌려준다. */
    @Transactional
    public int appendTurns(CurrentUser caller, UUID sessionId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        UUID betaSessionId = uuid(body.path("betaSessionId"));
        if (caller == null && betaSessionId == null) {
            throw ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401);
        }
        String storyId = shortText(body.path("storyId"), 64);
        if (storyId == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "storyId가 필요해요.");
        }
        JsonNode turns = body.path("turns");
        if (!turns.isArray() || turns.size() > MAX_TURNS_PER_REQUEST) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "대화는 한 번에 50개까지 보낼 수 있어요.");
        }
        upsertSession(caller, betaSessionId, sessionId, storyId, body);
        Instant now = Instant.now();
        for (JsonNode turn : turns) {
            insertTurn(sessionId, turn, now);
        }
        Integer max = jdbc.queryForObject(
                "select coalesce(max(seq), 0) from play_turn where session_id = ?", Integer.class, sessionId);
        return max == null ? 0 : max;
    }

    /** 기존 회차에 대한 호출자의 권한. */
    enum Access { OWNER, CLAIM, DENIED }

    /**
     * 로그인 호출자는 자기 user_id 회차의 주인이고, 아직 계정이 없는 같은 베타 세션 회차는 가져간다(CLAIM).
     * 익명 호출자는 계정이 없고 베타 세션 id가 같은 회차만 쓴다.
     */
    static Access access(UUID ownerUserId, UUID ownerBetaSessionId, UUID callerUserId, UUID callerBetaSessionId) {
        boolean sameBeta = callerBetaSessionId != null && callerBetaSessionId.equals(ownerBetaSessionId);
        if (callerUserId != null) {
            if (callerUserId.equals(ownerUserId)) return Access.OWNER;
            return ownerUserId == null && sameBeta ? Access.CLAIM : Access.DENIED;
        }
        return ownerUserId == null && sameBeta ? Access.OWNER : Access.DENIED;
    }

    record Owner(UUID userId, UUID betaSessionId) {}

    private List<Owner> owners(UUID sessionId) {
        return jdbc.query(
                "select user_id, beta_session_id from play_session where id = ?",
                (rs, rowNum) -> new Owner(rs.getObject("user_id", UUID.class), rs.getObject("beta_session_id", UUID.class)),
                sessionId);
    }

    private void upsertSession(CurrentUser caller, UUID betaSessionId, UUID sessionId, String storyId, JsonNode body) {
        UUID callerId = caller == null ? null : caller.userId();
        List<Owner> owners = owners(sessionId);
        String contentVersion = shortText(body.path("contentVersion"), 80);
        String readFrom = shortText(body.path("readFromSceneId"), 64);
        String readThrough = shortText(body.path("readThroughSceneId"), 64);
        Timestamp now = Timestamp.from(Instant.now());
        if (owners.isEmpty()) {
            // 아이·학생·수업은 로그인한 호출자 것일 때만 - 익명 데모 회차에는 붙지 않는다.
            jdbc.update(
                    "insert into play_session (id, user_id, beta_session_id, story_id, content_version, child_id, tutor_student_id, "
                            + "lesson_id, read_from_scene_id, read_through_scene_id, started_at, updated_at) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict (id) do nothing",
                    sessionId, callerId, betaSessionId, storyId, contentVersion,
                    caller == null ? null : ownedChild(caller, uuid(body.path("childId"))),
                    caller == null ? null : ownedStudent(caller, uuid(body.path("tutorStudentId"))),
                    caller == null ? null : ownedLesson(caller, uuid(body.path("lessonId"))),
                    readFrom, readThrough, now, now);
            owners = owners(sessionId);
        }
        Access access = owners.isEmpty()
                ? Access.DENIED
                : access(owners.get(0).userId(), owners.get(0).betaSessionId(), callerId, betaSessionId);
        if (access == Access.DENIED) {
            throw ApiException.contractError(ErrorCode.NOT_FOUND, "회차를 찾을 수 없어요.", 404);
        }
        if (access == Access.CLAIM) {
            // 데모로 시작해 로그인한 뒤 이어 보낸 회차 - 계정에 붙인다. 동시에 다른 계정이 가져갔으면 0행이라 404.
            int claimed = jdbc.update(
                    "update play_session set user_id = ? where id = ? and user_id is null and beta_session_id = ?",
                    callerId, sessionId, betaSessionId);
            if (claimed == 0) {
                throw ApiException.contractError(ErrorCode.NOT_FOUND, "회차를 찾을 수 없어요.", 404);
            }
        }
        if (betaSessionId != null) {
            // 로그인 회차에도 베타 세션을 남겨 두면 화면 녹화·상호작용과 같은 회차로 이어 볼 수 있다.
            jdbc.update("update play_session set beta_session_id = coalesce(beta_session_id, ?) where id = ?",
                    betaSessionId, sessionId);
        }
        // 사용 조건(진입 경로·진행 형태·기기)은 처음 값을 유지한다(Q-40).
        jdbc.update(
                "update play_session set entry_source = coalesce(entry_source, ?), play_setting = coalesce(play_setting, ?), "
                        + "device_platform = coalesce(device_platform, ?), device_browser = coalesce(device_browser, ?), "
                        + "viewport_class = coalesce(viewport_class, ?) where id = ?",
                shortText(body.path("entrySource"), 32), oneOf(body.path("playSetting"), PLAY_SETTINGS, null),
                shortText(body.path("devicePlatform"), 32), shortText(body.path("deviceBrowser"), 32),
                shortText(body.path("viewportClass"), 32), sessionId);
        // 읽은 범위는 앞으로만 넓힌다(처음 장면은 처음 값 유지, 마지막 장면은 새 값).
        jdbc.update(
                "update play_session set content_version = coalesce(?, content_version), "
                        + "read_from_scene_id = coalesce(read_from_scene_id, ?), "
                        + "read_through_scene_id = coalesce(?, read_through_scene_id), updated_at = ? where id = ?",
                contentVersion, readFrom, readThrough, now, sessionId);
    }

    private void insertTurn(UUID sessionId, JsonNode turn, Instant receivedAt) {
        int seq = turn.path("seq").asInt(0);
        String role = upper(turn.path("role"));
        String sceneId = shortText(turn.path("sceneId"), 64);
        if (seq <= 0 || role == null || !ROLES.contains(role) || sceneId == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "대화 줄에 seq·role·sceneId가 필요해요.");
        }
        String speaker = "CHILD".equals(role) ? oneOf(turn.path("speaker"), SPEAKERS, "UNVERIFIED") : null;
        jdbc.update(
                "insert into play_turn (session_id, seq, occurred_at, received_at, scene_id, visual_id, anchor_id, entry_mode, "
                        + "role, speaker, character_speaker_id, text, input_mode, transcript_edited, fixed, help_step, reply_kind, "
                        + "proposed_family_id, reply_audio_played, event, family_id, via_suggestion, suggestion_label, result_visual_id, "
                        + "latency_ms, error_code) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict (session_id, seq) do nothing",
                sessionId, seq, Timestamp.from(occurredAt(turn.path("occurredAt"), receivedAt)), Timestamp.from(receivedAt),
                sceneId, shortText(turn.path("visualId"), 80), shortText(turn.path("anchorId"), 64),
                oneOf(turn.path("entryMode"), ENTRY_MODES, null), role, speaker,
                shortText(turn.path("characterSpeakerId"), 64), longText(turn.path("text")),
                oneOf(turn.path("inputMode"), INPUT_MODES, null), bool(turn.path("transcriptEdited")),
                bool(turn.path("fixed")), turn.path("helpStep").isInt() ? turn.path("helpStep").asInt() : null,
                shortText(turn.path("replyKind"), 16), shortText(turn.path("proposedFamilyId"), 80),
                bool(turn.path("replyAudioPlayed")), oneOf(turn.path("event"), EVENTS, null),
                shortText(turn.path("familyId"), 80), bool(turn.path("viaSuggestion")),
                shortText(turn.path("suggestionLabel"), 80), shortText(turn.path("resultVisualId"), 80),
                turn.path("latencyMs").isInt() && turn.path("latencyMs").asInt() >= 0 ? turn.path("latencyMs").asInt() : null,
                shortText(turn.path("errorCode"), 60));
    }

    /** 리포트용 - 회차의 대화 전부를 seq 순으로. 프런트 계약(API-CONTRACT)의 turn 모양 그대로, 빈 값은 뺀다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTurns(UUID sessionId) {
        if (sessionId == null) return List.of();
        return jdbc.query(
                "select * from play_turn where session_id = ? order by seq",
                (rs, rowNum) -> {
                    Map<String, Object> turn = new LinkedHashMap<>();
                    turn.put("seq", rs.getInt("seq"));
                    turn.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
                    put(turn, "sceneId", rs.getString("scene_id"));
                    put(turn, "visualId", rs.getString("visual_id"));
                    put(turn, "anchorId", rs.getString("anchor_id"));
                    put(turn, "entryMode", rs.getString("entry_mode"));
                    put(turn, "role", rs.getString("role"));
                    put(turn, "speaker", rs.getString("speaker"));
                    put(turn, "characterSpeakerId", rs.getString("character_speaker_id"));
                    put(turn, "text", rs.getString("text"));
                    put(turn, "inputMode", rs.getString("input_mode"));
                    put(turn, "transcriptEdited", (Boolean) rs.getObject("transcript_edited"));
                    put(turn, "fixed", (Boolean) rs.getObject("fixed"));
                    put(turn, "helpStep", (Integer) rs.getObject("help_step"));
                    put(turn, "replyKind", rs.getString("reply_kind"));
                    put(turn, "proposedFamilyId", rs.getString("proposed_family_id"));
                    put(turn, "replyAudioPlayed", (Boolean) rs.getObject("reply_audio_played"));
                    put(turn, "event", rs.getString("event"));
                    put(turn, "familyId", rs.getString("family_id"));
                    put(turn, "viaSuggestion", (Boolean) rs.getObject("via_suggestion"));
                    put(turn, "suggestionLabel", rs.getString("suggestion_label"));
                    put(turn, "resultVisualId", rs.getString("result_visual_id"));
                    put(turn, "latencyMs", (Integer) rs.getObject("latency_ms"));
                    put(turn, "errorCode", rs.getString("error_code"));
                    return turn;
                },
                sessionId);
    }

    /** 이 회차에 저장된 대화가 한 번이라도 있었는지(지워졌는지 구분용) - 회차 행은 남고 줄만 지워진다. */
    @Transactional(readOnly = true)
    public boolean sessionExists(UUID sessionId) {
        if (sessionId == null) return false;
        Integer count = jdbc.queryForObject("select count(*) from play_session where id = ?", Integer.class, sessionId);
        return count != null && count > 0;
    }

    /**
     * 보관 기간이 지난 회차를 대화째 지운다(PlayTurnRetentionScheduler). 회차 행까지 지워야 리포트가 "원문이 지워졌다"
     * (turnsAvailable=false)를 알고 요약으로만 보여 준다.
     */
    @Transactional
    public int deleteSessionsUpdatedBefore(Instant cutoff) {
        return jdbc.update("delete from play_session where updated_at < ?", Timestamp.from(cutoff));
    }

    private UUID ownedChild(CurrentUser caller, UUID childId) {
        if (childId == null) return null;
        return exists("select count(*) from parent_child where id = ? and parent_id = ?", childId, caller.userId()) ? childId : null;
    }

    private UUID ownedStudent(CurrentUser caller, UUID studentId) {
        if (studentId == null) return null;
        return exists("select count(*) from tutor_student where id = ? and tutor_id = ? and deleted_at is null",
                studentId, caller.userId()) ? studentId : null;
    }

    private UUID ownedLesson(CurrentUser caller, UUID lessonId) {
        if (lessonId == null) return null;
        return exists("select count(*) from lesson where id = ? and tutor_id = ?", lessonId, caller.userId()) ? lessonId : null;
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) return null;
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String upper(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText().trim().toUpperCase() : null;
    }

    private static String oneOf(JsonNode node, Set<String> allowed, String fallback) {
        String value = upper(node);
        return value != null && allowed.contains(value) ? value : fallback;
    }

    private static String shortText(JsonNode node, int max) {
        if (node == null || !node.isTextual()) return null;
        String value = node.asText().trim();
        if (value.isEmpty()) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String longText(JsonNode node) {
        return shortText(node, MAX_TEXT);
    }

    private static Boolean bool(JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }

    private static Instant occurredAt(JsonNode node, Instant fallback) {
        if (node == null || !node.isTextual()) return fallback;
        try {
            Instant parsed = Instant.parse(node.asText());
            // 기기 시계가 크게 어긋나면 받은 시각을 쓴다.
            return parsed.isAfter(fallback.plusSeconds(300)) ? fallback : parsed;
        } catch (DateTimeParseException malformed) {
            return fallback;
        }
    }

    /** 테스트·분석용 - 아이 말만. */
    public static List<Map<String, Object>> childTurns(List<Map<String, Object>> turns) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> turn : turns) {
            if ("CHILD".equals(turn.get("role"))) result.add(turn);
        }
        return result;
    }
}
