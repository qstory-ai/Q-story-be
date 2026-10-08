package com.qstory.backend.org.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.GlobalExceptionHandler;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.dto.ClassHomeroomInvitePreviewResponse;
import com.qstory.backend.org.dto.ClassHomeroomInviteResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.service.ClassHomeroomInviteService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 074: 담임 초대 발급·조회는 원장만, 미리보기는 로그인 없이, 수락은 선생님만. */
class ClassHomeroomInviteControllerTest {

    private final ClassHomeroomInviteService service = mock(ClassHomeroomInviteService.class);
    private final UUID classId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ClassHomeroomInviteController(service, new CurrentUserResolver()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousCannotIssueReadOrAccept() throws Exception {
        mvc.perform(post("/v1/classes/" + classId + "/homeroom-invites")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/classes/" + classId + "/homeroom-invites/current")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/class-homeroom-invites/by-code/ABCD2345/accept")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void nonDirectorCannotIssueOrRead() throws Exception {
        for (Role role : List.of(Role.TUTOR, Role.PARENT)) {
            login(role);
            mvc.perform(post("/v1/classes/" + classId + "/homeroom-invites")).andExpect(status().isForbidden());
            mvc.perform(get("/v1/classes/" + classId + "/homeroom-invites/current")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void directorIssuesWith201() throws Exception {
        CurrentUser director = login(Role.DIRECTOR);
        when(service.issue(director, classId)).thenReturn(new ClassHomeroomInviteResponse(
                UUID.randomUUID(), "tok", "ABCD2345", Instant.parse("2026-10-22T00:00:00Z")));

        mvc.perform(post("/v1/classes/" + classId + "/homeroom-invites"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok"))
                .andExpect(jsonPath("$.shortCode").value("ABCD2345"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void currentIs404WhenNone() throws Exception {
        CurrentUser director = login(Role.DIRECTOR);
        when(service.current(director, classId))
                .thenThrow(ApiException.contractError(ErrorCode.NOT_FOUND, "없음", 404));
        mvc.perform(get("/v1/classes/" + classId + "/homeroom-invites/current")).andExpect(status().isNotFound());
    }

    @Test
    void previewNeedsNoLogin() throws Exception {
        when(service.preview("ABCD2345")).thenReturn(new ClassHomeroomInvitePreviewResponse(
                "햇살유치원", "햇님반", Instant.parse("2026-10-22T00:00:00Z"), null));
        mvc.perform(get("/v1/class-homeroom-invites/by-code/ABCD2345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName").value("햇살유치원"))
                .andExpect(jsonPath("$.className").value("햇님반"))
                .andExpect(jsonPath("$.currentHomeroomName").doesNotExist());
    }

    @Test
    void previewOfUsedCodeIs410() throws Exception {
        when(service.preview("USED2345"))
                .thenThrow(ApiException.contractError(ErrorCode.INVALID_INVITE, "만료", 410));
        mvc.perform(get("/v1/class-homeroom-invites/by-code/USED2345"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.failure.code").value("INVALID_INVITE"));
    }

    @Test
    void onlyTutorCanAccept() throws Exception {
        for (Role role : List.of(Role.DIRECTOR, Role.PARENT)) {
            login(role);
            mvc.perform(post("/v1/class-homeroom-invites/by-code/ABCD2345/accept")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);

        CurrentUser tutor = login(Role.TUTOR);
        UUID orgId = UUID.randomUUID();
        when(service.accept(eq(tutor), any())).thenReturn(
                new ClassResponse(classId, orgId, tutor.userId(), "햇님반", "SUN12345", Instant.now()));
        mvc.perform(post("/v1/class-homeroom-invites/by-code/ABCD2345/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tutorId").value(tutor.userId().toString()));
        verify(service).accept(tutor, "ABCD2345");
    }

    private CurrentUser login(Role role) {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), role, role == Role.DIRECTOR ? UUID.randomUUID() : null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user;
    }
}
