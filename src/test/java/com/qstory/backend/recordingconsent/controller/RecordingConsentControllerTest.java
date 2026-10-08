package com.qstory.backend.recordingconsent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qstory.backend.common.error.GlobalExceptionHandler;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.recordingconsent.service.RecordingConsentService;
import com.qstory.backend.recordingconsent.service.RecordingConsentService.Source;
import com.qstory.backend.recordingconsent.service.RecordingConsentService.Status;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 073: 베타 세션 동의는 로그인 없이 202, 계정 동의는 로그인 필수(401), 형식이 틀리면 400. */
class RecordingConsentControllerTest {

    private final RecordingConsentService service = mock(RecordingConsentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RecordingConsentController(service, new CurrentUserResolver()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousSessionDecisionIs202() throws Exception {
        UUID beta = UUID.randomUUID();
        mvc.perform(post("/v1/recording-consents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"betaSessionId\":\"" + beta + "\",\"granted\":false,\"source\":\"LESSON\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ok").value(true));
        verify(service).decideForBetaSession(beta, false, Source.LESSON);
    }

    @Test
    void badSessionDecisionIs400() throws Exception {
        for (String body : List.of("{\"granted\":true,\"source\":\"PROMPT\"}",
                "{\"betaSessionId\":\"" + UUID.randomUUID() + "\",\"granted\":true,\"source\":\"SIGNUP\"}",
                "{\"betaSessionId\":\"" + UUID.randomUUID() + "\",\"source\":\"PROMPT\"}",
                "not json", "")) {
            mvc.perform(post("/v1/recording-consents").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void accountConsentNeedsLogin() throws Exception {
        mvc.perform(get("/v1/me/recording-consent")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/me/recording-consent").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granted\":true,\"source\":\"ACCOUNT\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void readsUndecidedAccountConsentAsNulls() throws Exception {
        UUID user = login(Role.TUTOR);
        when(service.accountStatus(user)).thenReturn(new Status(null, null, null));
        mvc.perform(get("/v1/me/recording-consent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").isEmpty())
                .andExpect(jsonPath("$.source").isEmpty())
                .andExpect(jsonPath("$.decidedAt").isEmpty());
    }

    @Test
    void accountDecisionReturnsStatus() throws Exception {
        UUID user = login(Role.PARENT);
        when(service.decideForAccount(user, false, Source.ACCOUNT))
                .thenReturn(new Status(false, "ACCOUNT", "2026-10-08T10:00:00Z"));
        mvc.perform(post("/v1/me/recording-consent").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granted\":false,\"source\":\"ACCOUNT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(false))
                .andExpect(jsonPath("$.source").value("ACCOUNT"))
                .andExpect(jsonPath("$.decidedAt").value("2026-10-08T10:00:00Z"));
    }

    @Test
    void accountDecisionRejectsSessionOnlySource() throws Exception {
        login(Role.PARENT);
        mvc.perform(post("/v1/me/recording-consent").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granted\":true,\"source\":\"LESSON\"}"))
                .andExpect(status().isBadRequest());
        verify(service, org.mockito.Mockito.never()).decideForAccount(any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    private static UUID login(Role role) {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), role, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user.userId();
    }
}
