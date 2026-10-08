package com.qstory.backend.playsession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.playsession.service.PlaySessionService.Access;
import com.qstory.backend.playsession.service.PlaySessionService.Owner;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** 072: 로그인 전 데모 플레이도 회차로 남는다 - 주인은 베타 세션, 로그인하면 그 계정이 가져간다. */
class PlaySessionServiceAnonymousTest {

    private static final String OWNER_SQL = "select user_id, beta_session_id from play_session";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlaySessionService service = new PlaySessionService(jdbc);
    private final ObjectMapper json = new ObjectMapper();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final CurrentUser parent = new CurrentUser(userId, Role.PARENT, null);

    @Test
    void accessRules() {
        UUID other = UUID.randomUUID();
        // 로그인: 내 회차, 같은 베타 세션의 익명 회차는 가져가기, 남의 회차는 거절.
        assertThat(PlaySessionService.access(userId, null, userId, null)).isEqualTo(Access.OWNER);
        assertThat(PlaySessionService.access(null, beta, userId, beta)).isEqualTo(Access.CLAIM);
        assertThat(PlaySessionService.access(null, beta, userId, other)).isEqualTo(Access.DENIED);
        assertThat(PlaySessionService.access(null, beta, userId, null)).isEqualTo(Access.DENIED);
        assertThat(PlaySessionService.access(other, beta, userId, beta)).isEqualTo(Access.DENIED);
        // 익명: 같은 베타 세션의 익명 회차만. 계정에 붙은 회차는 베타 세션이 같아도 거절.
        assertThat(PlaySessionService.access(null, beta, null, beta)).isEqualTo(Access.OWNER);
        assertThat(PlaySessionService.access(null, beta, null, other)).isEqualTo(Access.DENIED);
        assertThat(PlaySessionService.access(userId, beta, null, beta)).isEqualTo(Access.DENIED);
    }

    @Test
    void anonymousWithoutBetaSessionIsUnauthenticated() throws Exception {
        assertThatThrownBy(() -> service.appendTurns(null, sessionId, body(null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                    assertThat(e.statusCode()).isEqualTo(401);
                });
    }

    @Test
    void anonymousCreatesSessionWithoutUser() throws Exception {
        ownersReturn(List.of(), List.of(new Owner(null, beta)));
        int saved = service.appendTurns(null, sessionId, body(beta.toString()));
        assertThat(saved).isZero();
        // user_id 자리는 null, beta_session_id 자리는 베타 세션. 아이·학생·수업 소유 확인 쿼리는 돌지 않는다.
        verify(jdbc).update(startsWith("insert into play_session"),
                eq(sessionId), isNull(), eq(beta), eq("story-1"), any(), isNull(), isNull(), isNull(), any(), any(), any(), any());
        verify(jdbc, never()).queryForObject(startsWith("select count(*) from parent_child"), eq(Integer.class), any(Object[].class));
    }

    @Test
    void anonymousCannotWriteToSomeoneElsesBetaSession() throws Exception {
        ownersReturn(List.of(new Owner(null, UUID.randomUUID())));
        assertThatThrownBy(() -> service.appendTurns(null, sessionId, body(beta.toString())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.statusCode()).isEqualTo(404));
    }

    @Test
    void loggedInCallerClaimsAnonymousSession() throws Exception {
        ownersReturn(List.of(new Owner(null, beta)));
        when(jdbc.update(startsWith("update play_session set user_id"), any(Object[].class))).thenReturn(1);
        service.appendTurns(parent, sessionId, body(beta.toString()));
        verify(jdbc).update(startsWith("update play_session set user_id"), eq(userId), eq(sessionId), eq(beta));
    }

    @Test
    void claimLostToAnotherAccountIs404() throws Exception {
        ownersReturn(List.of(new Owner(null, beta)));
        // update가 0행 - 그 사이 다른 계정이 가져갔다.
        assertThatThrownBy(() -> service.appendTurns(parent, sessionId, body(beta.toString())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.statusCode()).isEqualTo(404));
    }

    @Test
    void loggedInOwnerStillWorksWithoutBetaSession() throws Exception {
        ownersReturn(List.of(new Owner(userId, null)));
        service.appendTurns(parent, sessionId, body(null));
        verify(jdbc, never()).update(startsWith("update play_session set user_id"), any(Object[].class));
        verify(jdbc, never()).update(startsWith("update play_session set beta_session_id"), any(Object[].class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void ownersReturn(List<Owner> first, List<Owner>... next) {
        var stub = when(jdbc.query(startsWith(OWNER_SQL), any(RowMapper.class), eq(sessionId))).thenReturn((List) first);
        for (List<Owner> owners : next) stub = stub.thenReturn((List) owners);
    }

    private JsonNode body(String betaSessionId) throws Exception {
        String beta = betaSessionId == null ? "" : ",\"betaSessionId\":\"" + betaSessionId + "\"";
        return json.readTree("{\"storyId\":\"story-1\",\"childId\":\"" + UUID.randomUUID() + "\",\"turns\":[]" + beta + "}");
    }
}
