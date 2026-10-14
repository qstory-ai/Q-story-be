package com.qstory.backend.analytics.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qstory.backend.analytics.service.GaImportService;
import com.qstory.backend.common.error.GlobalExceptionHandler;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GA 가져오기 수동 실행은 STAFF만 - 비로그인 401, 셀프 가입 역할 403. */
class GaImportAdminControllerTest {

    private final GaImportService service = mock(GaImportService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new GaImportAdminController(service, new CurrentUserResolver()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(post("/v1/admin/ga-import")).andExpect(status().isUnauthorized());
        verify(service, never()).importRange(any(), any());
    }

    @Test
    void customerRolesAre403() throws Exception {
        for (Role role : List.of(Role.DIRECTOR, Role.TUTOR, Role.PARENT)) {
            login(role);
            mvc.perform(post("/v1/admin/ga-import")).andExpect(status().isForbidden());
        }
        verify(service, never()).importRange(any(), any());
    }

    @Test
    void staffRunsRangeEndingToday() throws Exception {
        login(Role.STAFF);
        when(service.enabled()).thenReturn(true);
        when(service.today()).thenReturn(LocalDate.of(2026, 10, 14));
        when(service.importRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14))).thenReturn(true);
        mvc.perform(post("/v1/admin/ga-import").param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.start").value("2026-10-01"))
                .andExpect(jsonPath("$.end").value("2026-10-14"));
    }

    @Test
    void disabledSaysSo() throws Exception {
        login(Role.STAFF);
        when(service.enabled()).thenReturn(false);
        mvc.perform(post("/v1/admin/ga-import"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.reason").value("disabled"));
        verify(service, never()).importRange(any(), any());
    }

    @Test
    void daysOutOfRangeIs400() throws Exception {
        login(Role.STAFF);
        mvc.perform(post("/v1/admin/ga-import").param("days", "0")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/ga-import").param("days", "91")).andExpect(status().isBadRequest());
    }

    private static void login(Role role) {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), role, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
