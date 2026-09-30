package com.qstory.backend.tutor.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.storyreport.dto.StoryCompletionSummary;
import com.qstory.backend.tutor.dto.TutorReportSummary;
import com.qstory.backend.tutor.dto.TutorStudentResponse;
import com.qstory.backend.tutor.dto.UpdateTutorStudentRequest;
import com.qstory.backend.tutor.service.TutorReportService;
import com.qstory.backend.tutor.service.TutorStudentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tutors", description = "Class roster students (joined by parents through class invite links) and shared reports")
@RestController
public class TutorController {

    private final TutorStudentService service;
    private final TutorReportService reportService;
    private final CurrentUserResolver currentUserResolver;

    public TutorController(TutorStudentService service, TutorReportService reportService, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.reportService = reportService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List the caller's own students", description = "TUTOR only.")
    @GetMapping("/v1/tutor-students")
    public List<TutorStudentResponse> listStudents() {
        return service.listStudents(currentUserResolver.requireRole(Role.TUTOR));
    }

    @Operation(summary = "Get one of the caller's students", description = "TUTOR only. Must own the student.")
    @GetMapping("/v1/tutor-students/{studentId}")
    public TutorStudentResponse getStudent(@PathVariable UUID studentId) {
        return service.getStudent(currentUserResolver.requireRole(Role.TUTOR), studentId);
    }

    @Operation(summary = "Update a student's memos",
            description = "TUTOR only. Partial update - null 필드는 그대로 두고 빈 문자열은 지우기로 해석.")
    @PatchMapping("/v1/tutor-students/{studentId}")
    public TutorStudentResponse updateStudent(
            @PathVariable UUID studentId, @RequestBody UpdateTutorStudentRequest request) {
        return service.updateStudent(currentUserResolver.requireRole(Role.TUTOR), studentId, request);
    }

    @Operation(summary = "Remove a student from the roster (soft delete)",
            description = "TUTOR only, must own the student. Drops the student from scheduled lessons; past lesson reports stay.")
    @DeleteMapping("/v1/tutor-students/{studentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStudent(@PathVariable UUID studentId) {
        service.deleteStudent(currentUserResolver.requireRole(Role.TUTOR), studentId);
    }

    @Operation(summary = "List a student's session reports", description = "TUTOR only, must own the student.")
    @GetMapping("/v1/tutor-students/{studentId}/completions")
    public List<StoryCompletionSummary> listStudentCompletions(@PathVariable UUID studentId) {
        return reportService.listStudentCompletions(currentUserResolver.requireRole(Role.TUTOR), studentId);
    }

    @Operation(summary = "List reports a parent has received from connected tutors",
            description = "PARENT only. Never includes the parent's own home-library completions - only sessions "
                    + "a connected tutor conducted with a linked student.")
    @GetMapping("/v1/parents/me/tutor-reports")
    public List<TutorReportSummary> listReportsForParent() {
        return reportService.listReportsForParent(currentUserResolver.requireRole(Role.PARENT));
    }
}
