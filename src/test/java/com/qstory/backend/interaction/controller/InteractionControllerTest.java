package com.qstory.backend.interaction.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.GlobalExceptionHandler;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.interaction.service.InteractionService;
import com.qstory.backend.interaction.service.UsageTrackingService;
import java.time.Instant;
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

/** 073: 로그인한 사용자가 화면 이용 기록을 끄면 상호작용을 저장하지 않는다(202 accepted 0). 스위치는 로그인 필수. */
class InteractionControllerTest {

    private final InteractionService interactions = mock(InteractionService.class);
    private final UsageTrackingService usageTracking = mock(UsageTrackingService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CurrentUserResolver resolver = new CurrentUserResolver();
        mvc = MockMvcBuilders.standaloneSetup(
                        new InteractionController(new ObjectMapper(), interactions, resolver, usageTracking),
                        new UsageTrackingController(usageTracking, resolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(interactions.record(any(), any())).thenReturn(1);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousInteractionsAreStored() throws Exception {
        send().andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(1));
        verify(interactions).record(any(), eq(null));
        verify(usageTracking, never()).isEnabled(any());
    }

    @Test
    void signedInWithTrackingOnIsStored() throws Exception {
        UUID user = login();
        when(usageTracking.isEnabled(user)).thenReturn(true);
        send().andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(1));
        verify(interactions).record(any(), eq(user));
    }

    @Test
    void signedInWithTrackingOffIsDropped() throws Exception {
        UUID user = login();
        when(usageTracking.isEnabled(user)).thenReturn(false);
        send().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.accepted").value(0));
        verify(interactions, never()).record(any(), any());
    }

    @Test
    void usageTrackingSwitchNeedsLogin() throws Exception {
        mvc.perform(get("/v1/me/usage-tracking")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/me/usage-tracking").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usageTrackingSwitchReadsAndWrites() throws Exception {
        UUID user = login();
        when(usageTracking.isEnabled(user)).thenReturn(true);
        when(usageTracking.setEnabled(user, false)).thenReturn(false);
        mvc.perform(get("/v1/me/usage-tracking")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/v1/me/usage-tracking").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/v1/me/usage-tracking").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":\"no\"}"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions send() throws Exception {
        String body = "{\"betaSessionId\":\"" + UUID.randomUUID() + "\",\"events\":[{\"kind\":\"TAP\",\"occurredAt\":\""
                + Instant.now() + "\"}]}";
        return mvc.perform(post("/v1/interactions").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static UUID login() {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), Role.PARENT, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user.userId();
    }
}
