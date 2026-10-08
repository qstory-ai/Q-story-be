package com.qstory.backend.recordingconsent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.recordingconsent.service.RecordingConsentService.Source;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 073: 화면 녹화 동의 - 입력 검사, 철회 시 녹화 삭제, 조각 수집 허용(나중 결정이 이긴다). */
class RecordingConsentServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RecordingConsentService service = new RecordingConsentService(jdbc);
    private final UUID beta = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void parsesSessionDecision() {
        ObjectNode body = json.createObjectNode().put("betaSessionId", beta.toString()).put("granted", true).put("source", "UT_LINK");
        RecordingConsentService.Decision decision =
                RecordingConsentService.parse(body, RecordingConsentService.SESSION_SOURCES, true);
        assertThat(decision.betaSessionId()).isEqualTo(beta);
        assertThat(decision.granted()).isTrue();
        assertThat(decision.source()).isEqualTo(Source.UT_LINK);
    }

    @Test
    void badInputIs400() {
        ObjectNode ok = json.createObjectNode().put("betaSessionId", beta.toString()).put("granted", true).put("source", "PROMPT");
        assertBadRequest(ok.deepCopy().put("betaSessionId", "nope"), true);
        assertBadRequest(ok.deepCopy().put("granted", "true"), true);
        assertBadRequest(ok.deepCopy().put("source", "ACCOUNT"), true); // 계정 단위 출처는 베타 세션으로 못 남긴다
        assertBadRequest(ok.deepCopy().put("source", "prompt"), true);
        assertBadRequest(ok.deepCopy().put("source", "UT_LINK"), false); // 계정 단위로는 UT_LINK 불가
        assertThatThrownBy(() -> RecordingConsentService.parse(null, RecordingConsentService.SESSION_SOURCES, true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.statusCode()).isEqualTo(400));
    }

    private void assertBadRequest(ObjectNode body, boolean session) {
        assertThatThrownBy(() -> RecordingConsentService.parse(body,
                session ? RecordingConsentService.SESSION_SOURCES : RecordingConsentService.ACCOUNT_SOURCES, session))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.statusCode()).isEqualTo(400));
    }

    @Test
    void sessionGrantUpsertsWithoutDeleting() {
        service.decideForBetaSession(beta, true, Source.PROMPT);
        verify(jdbc).update(contains("on conflict (beta_session_id) where user_id is null"), eq(beta), eq(true), eq("PROMPT"), any());
        assertNoChunkDelete();
    }

    @Test
    void sessionWithdrawalDeletesItsChunks() {
        service.decideForBetaSession(beta, false, Source.PROMPT);
        verify(jdbc).update(contains("on conflict (beta_session_id) where user_id is null"), eq(beta), eq(false), eq("PROMPT"), any());
        verify(jdbc).update("delete from session_recording_chunk where beta_session_id = ?", beta);
    }

    @Test
    void accountWithdrawalDeletesLinkedChunksAndRevokesLinkedSessions() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(user))).thenReturn(List.of(
                new RecordingConsentService.Status(false, "ACCOUNT", "2026-10-08T10:00:00Z")));

        RecordingConsentService.Status status = service.decideForAccount(user, false, Source.ACCOUNT);

        assertThat(status.granted()).isFalse();
        verify(jdbc).update(contains("on conflict (user_id) where beta_session_id is null"), eq(user), eq(false), eq("ACCOUNT"), any());
        verify(jdbc).update(startsWith("delete from session_recording_chunk where user_id = ? or beta_session_id in ("),
                eq(user), eq(user), eq(user));
        verify(jdbc).update(startsWith("update screen_recording_consent set granted = false"), any(), eq(user), eq(user), eq(user));
    }

    @Test
    void accountGrantDeletesNothing() {
        service.decideForAccount(user, true, Source.SIGNUP);
        assertNoChunkDelete();
    }

    private void assertNoChunkDelete() {
        assertThat(org.mockito.Mockito.mockingDetails(jdbc).getInvocations())
                .noneMatch(call -> call.getArguments().length > 0 && call.getArguments()[0] instanceof String sql
                        && sql.startsWith("delete from session_recording_chunk"));
    }

    @Test
    void noDecisionMeansNoStatus() {
        RecordingConsentService.Status status = service.accountStatus(user);
        assertThat(status.granted()).isNull();
        assertThat(status.source()).isNull();
        assertThat(status.decidedAt()).isNull();
    }

    @Test
    void anonymousUploadNeedsGrantedSessionConsent() {
        String sql = "select granted from screen_recording_consent where beta_session_id = ? and user_id is null";
        when(jdbc.queryForList(sql, Boolean.class, beta)).thenReturn(List.of());
        assertThat(service.uploadAllowed(beta, null)).isFalse();
        assertThatThrownBy(() -> service.requireUploadAllowed(beta, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo(ErrorCode.RECORDING_NOT_CONSENTED);
                });

        when(jdbc.queryForList(sql, Boolean.class, beta)).thenReturn(List.of(true));
        assertThat(service.uploadAllowed(beta, null)).isTrue();

        when(jdbc.queryForList(sql, Boolean.class, beta)).thenReturn(List.of(false));
        assertThat(service.uploadAllowed(beta, null)).isFalse();
    }

    @Test
    void signedInUploadFollowsTheLatestOfSessionAndAccountDecision() {
        when(jdbc.queryForList(contains("order by decided_at desc"), eq(Boolean.class), eq(beta), eq(user)))
                .thenReturn(List.of(true));
        assertThat(service.uploadAllowed(beta, user)).isTrue();

        when(jdbc.queryForList(contains("order by decided_at desc"), eq(Boolean.class), eq(beta), eq(user)))
                .thenReturn(List.of(false));
        assertThat(service.uploadAllowed(beta, user)).isFalse();

        when(jdbc.queryForList(contains("order by decided_at desc"), eq(Boolean.class), eq(beta), eq(user)))
                .thenReturn(List.of());
        assertThat(service.uploadAllowed(beta, user)).isFalse();
    }

    @Test
    void erasureDeletesRecordingsAndConsentRows() {
        service.eraseForAccount(user);
        verify(jdbc).update(startsWith("delete from session_recording_chunk where user_id = ? or beta_session_id in ("),
                eq(user), eq(user), eq(user));
        verify(jdbc).update("delete from screen_recording_consent where user_id = ?", user);
    }
}
