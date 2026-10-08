package com.qstory.backend.sessionrecording.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.GlobalExceptionHandler;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.recordingconsent.service.RecordingConsentService;
import com.qstory.backend.sessionrecording.service.SessionRecordingService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 073: 화면 녹화 조각은 동의(베타 세션 또는 로그인한 계정)가 있을 때만 받는다. 조각마다 확인하므로 철회 뒤엔 403. */
class SessionRecordingControllerTest {

    private static final String SESSION_ONLY =
            "select granted from screen_recording_consent where beta_session_id = ? and user_id is null";

    private final SessionRecordingService recordings = mock(SessionRecordingService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID beta = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SessionRecordingController controller = new SessionRecordingController(
                new ObjectMapper(), recordings, new CurrentUserResolver(), new RecordingConsentService(jdbc));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(recordings.store(any(), any())).thenReturn(true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noConsentIs403() throws Exception {
        when(jdbc.queryForList(SESSION_ONLY, Boolean.class, beta)).thenReturn(List.of());
        upload()
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.failure.code").value("RECORDING_NOT_CONSENTED"));
        verify(recordings, never()).store(any(), any());
    }

    @Test
    void betaSessionConsentIsEnough() throws Exception {
        when(jdbc.queryForList(SESSION_ONLY, Boolean.class, beta)).thenReturn(List.of(true));
        upload().andExpect(status().isAccepted()).andExpect(jsonPath("$.stored").value(true));
        verify(recordings).store(any(), eq(null));
    }

    @Test
    void accountConsentIsEnoughWhenSignedIn() throws Exception {
        UUID user = login();
        when(jdbc.queryForList(contains("order by decided_at desc"), eq(Boolean.class), eq(beta), eq(user)))
                .thenReturn(List.of(true));
        upload().andExpect(status().isAccepted());
        verify(recordings).store(any(), eq(user));
    }

    @Test
    void withdrawalStopsTheNextChunk() throws Exception {
        when(jdbc.queryForList(SESSION_ONLY, Boolean.class, beta)).thenReturn(List.of(true), List.of(false));
        upload().andExpect(status().isAccepted());
        upload().andExpect(status().isForbidden());
        verify(recordings).store(any(), any());
    }

    @Test
    void withdrawnAccountIs403EvenIfTheSessionOnceAgreed() throws Exception {
        UUID user = login();
        // 계정 철회가 세션 동의보다 나중이면 최신 결정(false)이 돌아온다.
        when(jdbc.queryForList(contains("order by decided_at desc"), eq(Boolean.class), eq(beta), eq(user)))
                .thenReturn(List.of(false));
        upload().andExpect(status().isForbidden());
        verify(recordings, never()).store(any(), any());
    }

    private org.springframework.test.web.servlet.ResultActions upload() throws Exception {
        return mvc.perform(post("/v1/session-recordings/chunks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"betaSessionId\":\"" + beta + "\",\"seq\":0,\"encoding\":\"json\",\"data\":\"[]\"}"));
    }

    private static UUID login() {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), Role.PARENT, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user.userId();
    }
}
