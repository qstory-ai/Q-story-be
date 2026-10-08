package com.qstory.backend.sessionrecording.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qstory.backend.common.error.GlobalExceptionHandler;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.sessionrecording.service.SessionRecordingService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 화면 녹화 재생은 내부 운영 역할(STAFF)만 - 셀프 가입 역할(원장·선생님·보호자)은 403, 비로그인은 401. */
class SessionRecordingAdminControllerTest {

    private final SessionRecordingService service = mock(SessionRecordingService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SessionRecordingAdminController(service, new CurrentUserResolver()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/v1/admin/session-recordings").param("code", "ABC123")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerRolesAre403() throws Exception {
        for (Role role : List.of(Role.DIRECTOR, Role.TUTOR, Role.PARENT)) {
            login(role);
            mvc.perform(get("/v1/admin/session-recordings").param("code", "ABC123")).andExpect(status().isForbidden());
            mvc.perform(get("/v1/admin/session-recordings/" + UUID.randomUUID() + "/chunks")).andExpect(status().isForbidden());
        }
    }

    @Test
    void staffReadsChunks() throws Exception {
        login(Role.STAFF);
        UUID beta = UUID.randomUUID();
        when(service.listChunks(beta)).thenReturn(List.of(Map.of("seq", 0, "encoding", "json", "data", "[]")));
        mvc.perform(get("/v1/admin/session-recordings/" + beta + "/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunks[0].seq").value(0))
                .andExpect(jsonPath("$.chunks[0].data").value("[]"));
    }

    private static void login(Role role) {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), role, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
