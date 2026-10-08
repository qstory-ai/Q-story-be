package com.qstory.backend.org.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.GlobalExceptionHandler;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.MoveStudentsRequest;
import com.qstory.backend.org.dto.MoveStudentsResponse;
import com.qstory.backend.org.dto.RenameClassRequest;
import com.qstory.backend.org.dto.SkippedStudent;
import com.qstory.backend.org.dto.TermTransitionRequest;
import com.qstory.backend.org.dto.TermTransitionResponse;
import com.qstory.backend.org.service.ClassLifecycleService;
import com.qstory.backend.org.service.ClassService;
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

/** 076: 반 수명주기 경로 - 바꾸는 요청은 원장만, JSON 계약(archivedAt, moved/skipped, 학기 넘기기 요약), 쿼리 파라미터. */
class ClassControllerLifecycleTest {

    private final ClassService service = mock(ClassService.class);
    private final ClassLifecycleService lifecycleService = mock(ClassLifecycleService.class);
    private final UUID classId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ClassController(service, lifecycleService, new CurrentUserResolver()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonDirectorsCannotChangeClasses() throws Exception {
        for (Role role : List.of(Role.TUTOR, Role.PARENT)) {
            login(role);
            mvc.perform(patch("/v1/classes/" + classId).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/v1/classes/" + classId + "/archive")).andExpect(status().isForbidden());
            mvc.perform(post("/v1/classes/" + classId + "/unarchive")).andExpect(status().isForbidden());
            mvc.perform(post("/v1/classes/" + classId + "/students/move").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"studentIds\":[],\"targetClassId\":null}")).andExpect(status().isForbidden());
            mvc.perform(post("/v1/classes/" + classId + "/term-transition").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decisions\":[],\"archiveClass\":true}")).andExpect(status().isForbidden());
        }
        SecurityContextHolder.clearContext();
        mvc.perform(post("/v1/classes/" + classId + "/archive")).andExpect(status().isUnauthorized());
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void renameAndArchiveReturnClassWithArchivedAt() throws Exception {
        CurrentUser director = login(Role.DIRECTOR);
        Instant archivedAt = Instant.parse("2027-02-28T00:00:00Z");
        when(lifecycleService.rename(director, classId, new RenameClassRequest("해님반")))
                .thenReturn(new ClassResponse(classId, orgId, null, "해님반", "SUN12345", Instant.now(), null));
        when(lifecycleService.archive(director, classId))
                .thenReturn(new ClassResponse(classId, orgId, null, "해님반", "SUN12345", Instant.now(), archivedAt));

        mvc.perform(patch("/v1/classes/" + classId).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"해님반\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("해님반"))
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        mvc.perform(post("/v1/classes/" + classId + "/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
    }

    @Test
    void archiveWithStudentsIs409WithCode() throws Exception {
        CurrentUser director = login(Role.DIRECTOR);
        when(lifecycleService.archive(director, classId)).thenThrow(
                ApiException.contractError(ErrorCode.CLASS_HAS_ACTIVE_STUDENTS, "아직 이 반에 학생이 있어요.", 409));

        mvc.perform(post("/v1/classes/" + classId + "/archive"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failure.code").value("CLASS_HAS_ACTIVE_STUDENTS"));
    }

    @Test
    void moveAndTermTransitionJsonContract() throws Exception {
        CurrentUser director = login(Role.DIRECTOR);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(lifecycleService.moveStudents(director, classId, new MoveStudentsRequest(List.of(a, b), target)))
                .thenReturn(new MoveStudentsResponse(List.of(a), List.of(new SkippedStudent(b, SkippedStudent.ALREADY_IN_TARGET))));
        mvc.perform(post("/v1/classes/" + classId + "/students/move").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[\"" + a + "\",\"" + b + "\"],\"targetClassId\":\"" + target + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved[0]").value(a.toString()))
                .andExpect(jsonPath("$.skipped[0].studentId").value(b.toString()))
                .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_IN_TARGET"));

        when(lifecycleService.termTransition(eq(director), eq(classId), any(TermTransitionRequest.class)))
                .thenReturn(new TermTransitionResponse(List.of(a), List.of(), List.of(b), true, List.of()));
        mvc.perform(post("/v1/classes/" + classId + "/term-transition").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":[{\"studentId\":\"" + a + "\",\"action\":\"MOVE\",\"targetClassId\":\""
                                + target + "\"},{\"studentId\":\"" + b + "\",\"action\":\"GRADUATE\"}],\"archiveClass\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved[0]").value(a.toString()))
                .andExpect(jsonPath("$.graduated[0]").value(b.toString()))
                .andExpect(jsonPath("$.kept").isEmpty())
                .andExpect(jsonPath("$.archived").value(true));
        verify(lifecycleService).termTransition(director, classId, new TermTransitionRequest(List.of(
                new TermTransitionRequest.Decision(a, "MOVE", target),
                new TermTransitionRequest.Decision(b, "GRADUATE", null)), true));
    }

    @Test
    void listFlagsAreForwarded() throws Exception {
        CurrentUser director = login(Role.DIRECTOR);
        when(service.list(director, orgId, true)).thenReturn(List.of());
        when(service.list(director, orgId, false)).thenReturn(List.of());
        when(service.listStudents(director, classId, true)).thenReturn(List.of());

        mvc.perform(get("/v1/organizations/" + orgId + "/classes?includeArchived=true")).andExpect(status().isOk());
        mvc.perform(get("/v1/organizations/" + orgId + "/classes")).andExpect(status().isOk());
        mvc.perform(get("/v1/classes/" + classId + "/students?includePast=true")).andExpect(status().isOk());
        verify(service).list(director, orgId, true);
        verify(service).list(director, orgId, false);
        verify(service).listStudents(director, classId, true);
    }

    @Test
    void classHistoryIsOpenToTutorsToo() throws Exception {
        CurrentUser tutor = login(Role.TUTOR);
        UUID studentId = UUID.randomUUID();
        when(lifecycleService.classHistory(tutor, studentId)).thenReturn(List.of());

        mvc.perform(get("/v1/tutor-students/" + studentId + "/class-history")).andExpect(status().isOk());
        verify(lifecycleService).classHistory(tutor, studentId);
    }

    private CurrentUser login(Role role) {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), role, role == Role.DIRECTOR ? orgId : null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user;
    }
}
