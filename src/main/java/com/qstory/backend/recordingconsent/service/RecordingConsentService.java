package com.qstory.backend.recordingconsent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화면 녹화 동의(073 screen_recording_consent). 화면 녹화는 선택 정보라 동의한 경우에만 조각을 받는다.
 *
 * <p>동의는 두 단위로 남는다 - 베타 세션 단위(스토리 시작 전 물음, UT 링크 방문자, 보호자 동의를 받은 수업)와 계정 단위
 * (마이페이지 스위치, 가입 때 선택 체크). 한 주체당 마지막 결정 한 행만 둔다. 조각을 받을 때는 이 베타 세션의 결정과
 * (로그인했다면) 이 계정의 결정 중 <b>나중에 내린 결정</b>을 따른다 - 계정에서 철회하면 그 뒤로는 예전에 이 세션에서 받은
 * 동의가 있어도 막히고, 철회 뒤 이 세션에서 다시 동의하면 이 세션만 다시 받는다.
 *
 * <p>철회(granted=false)는 이미 받은 녹화도 지운다.
 */
@Service
public class RecordingConsentService {

    public enum Source { PROMPT, ACCOUNT, SIGNUP, UT_LINK, LESSON }

    /** 로그인 없이 베타 세션 단위로 남길 수 있는 출처. */
    public static final Set<Source> SESSION_SOURCES = EnumSet.of(Source.PROMPT, Source.UT_LINK, Source.LESSON);
    /** 계정 단위로 남길 수 있는 출처. */
    public static final Set<Source> ACCOUNT_SOURCES = EnumSet.of(Source.ACCOUNT, Source.SIGNUP, Source.PROMPT);

    public record Decision(UUID betaSessionId, boolean granted, Source source) {}

    /** granted·source·decidedAt 모두 결정한 적이 없으면 null. */
    public record Status(Boolean granted, String source, String decidedAt) {
        static final Status NONE = new Status(null, null, null);
    }

    /** 이 계정과 이어진 베타 세션들 - 회차(play_session)·통계 세션(story_sessions)·이 계정으로 올린 녹화 조각. */
    private static final String LINKED_BETA_SESSIONS =
            "select s.beta_session_id from play_session s where s.user_id = ? and s.beta_session_id is not null"
                    + " union select ss.id from story_sessions ss where ss.user_id = ?";

    private final JdbcTemplate jdbc;

    public RecordingConsentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 요청 본문 {betaSessionId?, granted, source}를 검사한다. 틀리면 400. */
    public static Decision parse(JsonNode body, Set<Source> allowedSources, boolean betaSessionRequired) {
        if (body == null || !body.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        UUID betaSessionId = null;
        if (betaSessionRequired) {
            betaSessionId = uuid(body.path("betaSessionId"));
            if (betaSessionId == null) {
                throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "betaSessionId가 필요해요.");
            }
        }
        JsonNode granted = body.path("granted");
        if (!granted.isBoolean()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "granted는 true 또는 false여야 해요.");
        }
        Source source = source(body.path("source"));
        if (source == null || !allowedSources.contains(source)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "알 수 없는 동의 경로예요.");
        }
        return new Decision(betaSessionId, granted.asBoolean(), source);
    }

    /**
     * 베타 세션 단위 결정을 남겨도 되는지(보안 점검 M5·L3). 안 되면 401/403/404를 던진다. 돌려주는 값은 결정한 계정(익명이면 null).
     * <ul>
     *   <li>LESSON(보호자 동의를 받은 수업)은 로그인한 선생님·원장만 남긴다. lessonId를 보냈으면 그 계정의 수업이어야 한다.</li>
     *   <li>계정과 이어진 베타 세션(회차·통계 세션으로 이어짐)은 그 계정으로 로그인해야 바꾼다 - 베타 세션 id만 아는 사람이 남의
     *   녹화를 지우거나 켜지 못하게 한다. 아직 계정과 이어지지 않은 익명 베타 세션은 그대로 로그인 없이 바꾼다.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public UUID authorizeSessionDecision(Decision decision, UUID callerUserId, Role callerRole, UUID lessonId) {
        if (decision.source() == Source.LESSON) {
            if (callerUserId == null) {
                throw ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401);
            }
            if (callerRole != Role.TUTOR && callerRole != Role.DIRECTOR) {
                throw ApiException.contractError(ErrorCode.FORBIDDEN, "수업 녹화 동의는 선생님만 남길 수 있어요.", 403);
            }
            if (lessonId != null && !exists("select count(*) from lesson where id = ? and tutor_id = ?", lessonId, callerUserId)) {
                throw ApiException.contractError(ErrorCode.NOT_FOUND, "수업을 찾을 수 없어요.", 404);
            }
        }
        List<UUID> linkedAccounts = jdbc.queryForList(
                "select s.user_id from play_session s where s.beta_session_id = ? and s.user_id is not null"
                        + " union select ss.user_id from story_sessions ss where ss.id = ? and ss.user_id is not null",
                UUID.class, decision.betaSessionId(), decision.betaSessionId());
        if (!linkedAccounts.isEmpty() && (callerUserId == null || !linkedAccounts.contains(callerUserId))) {
            throw ApiException.contractError(
                    ErrorCode.FORBIDDEN, "계정과 이어진 기록이에요. 그 계정으로 로그인한 뒤 바꿔 주세요.", 403);
        }
        return callerUserId;
    }

    /** 베타 세션 단위 결정을 남긴다. 철회면 이 베타 세션의 녹화 조각을 지운다. */
    @Transactional
    public void decideForBetaSession(UUID betaSessionId, boolean granted, Source source) {
        decideForBetaSession(betaSessionId, granted, source, null, null);
    }

    /** decidedBy·lessonId - 결정한 계정과 수업(079). 수업 동의(LESSON)를 누가 어느 수업에서 확인했는지 남는다. */
    @Transactional
    public void decideForBetaSession(UUID betaSessionId, boolean granted, Source source, UUID decidedBy, UUID lessonId) {
        jdbc.update(
                "insert into screen_recording_consent (beta_session_id, user_id, granted, source, decided_at, decided_by, lesson_id) "
                        + "values (?, null, ?, ?, ?, ?, ?) "
                        + "on conflict (beta_session_id) where user_id is null "
                        + "do update set granted = excluded.granted, source = excluded.source, decided_at = excluded.decided_at, "
                        + "decided_by = excluded.decided_by, lesson_id = excluded.lesson_id",
                betaSessionId, granted, source.name(), Timestamp.from(Instant.now()), decidedBy, lessonId);
        if (!granted) {
            jdbc.update("delete from session_recording_chunk where beta_session_id = ?", betaSessionId);
        }
    }

    @Transactional(readOnly = true)
    public Status accountStatus(UUID userId) {
        List<Status> rows = jdbc.query(
                "select granted, source, decided_at from screen_recording_consent "
                        + "where user_id = ? and beta_session_id is null",
                (rs, rowNum) -> new Status(
                        rs.getBoolean("granted"), rs.getString("source"),
                        rs.getTimestamp("decided_at").toInstant().toString()),
                userId);
        return rows.isEmpty() ? Status.NONE : rows.get(0);
    }

    /**
     * 계정 단위 결정을 남긴다. 철회면 이 계정으로 올린 녹화와 이 계정과 이어진 베타 세션의 녹화를 지우고, 그 베타 세션들에
     * 남아 있던 동의도 철회로 바꾼다(로그아웃한 채로 같은 세션에서 계속 올라오지 않게).
     */
    @Transactional
    public Status decideForAccount(UUID userId, boolean granted, Source source) {
        Instant now = Instant.now();
        jdbc.update(
                "insert into screen_recording_consent (beta_session_id, user_id, granted, source, decided_at) "
                        + "values (null, ?, ?, ?, ?) "
                        + "on conflict (user_id) where beta_session_id is null "
                        + "do update set granted = excluded.granted, source = excluded.source, decided_at = excluded.decided_at",
                userId, granted, source.name(), Timestamp.from(now));
        if (!granted) {
            revokeLinkedSessionConsents(userId, now);
            deleteRecordingsOf(userId);
        }
        return accountStatus(userId);
    }

    /**
     * 이 베타 세션의 조각을 지금 받아도 되는지. 베타 세션 결정과 (로그인했다면) 계정 결정 중 나중 것을 따른다. 결정이
     * 하나도 없으면 받지 않는다.
     */
    @Transactional(readOnly = true)
    public boolean uploadAllowed(UUID betaSessionId, UUID userId) {
        List<Boolean> latest = userId == null
                ? jdbc.queryForList(
                        "select granted from screen_recording_consent where beta_session_id = ? and user_id is null",
                        Boolean.class, betaSessionId)
                : jdbc.queryForList(
                        "select granted from screen_recording_consent "
                                + "where (beta_session_id = ? and user_id is null) or (user_id = ? and beta_session_id is null) "
                                + "order by decided_at desc, id desc limit 1",
                        Boolean.class, betaSessionId, userId);
        return !latest.isEmpty() && Boolean.TRUE.equals(latest.get(0));
    }

    /** 동의가 없거나 철회됐으면 403 RECORDING_NOT_CONSENTED. */
    public void requireUploadAllowed(UUID betaSessionId, UUID userId) {
        if (!uploadAllowed(betaSessionId, userId)) {
            throw ApiException.contractError(ErrorCode.RECORDING_NOT_CONSENTED, "화면 녹화 동의가 없어요.", 403);
        }
    }

    /**
     * 회원 탈퇴(AccountErasureService). 계정 행은 익명화로 남아 on delete cascade가 돌지 않으므로 직접 지운다 - 이 계정과
     * 이어진 녹화 조각과 계정 동의 행을 지우고, 이어진 베타 세션의 동의는 철회로 바꾼다.
     */
    @Transactional
    public void eraseForAccount(UUID userId) {
        revokeLinkedSessionConsents(userId, Instant.now());
        deleteRecordingsOf(userId);
        jdbc.update("delete from screen_recording_consent where user_id = ?", userId);
    }

    private void revokeLinkedSessionConsents(UUID userId, Instant now) {
        jdbc.update(
                "update screen_recording_consent set granted = false, decided_at = ? "
                        + "where user_id is null and granted and beta_session_id in ("
                        + LINKED_BETA_SESSIONS
                        + " union select c.beta_session_id from session_recording_chunk c where c.user_id = ?)",
                Timestamp.from(now), userId, userId, userId);
    }

    private void deleteRecordingsOf(UUID userId) {
        jdbc.update(
                "delete from session_recording_chunk where user_id = ? or beta_session_id in (" + LINKED_BETA_SESSIONS + ")",
                userId, userId, userId);
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    /** 본문의 선택 필드 lessonId. 없거나 형식이 틀리면 null. */
    public static UUID lessonId(JsonNode body) {
        return body == null ? null : uuid(body.path("lessonId"));
    }

    private static Source source(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        try {
            return Source.valueOf(node.asText().trim());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) return null;
        try {
            return UUID.fromString(node.asText().trim());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
