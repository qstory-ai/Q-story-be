package com.qstory.backend.tutor.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.storyreport.dto.StoryCompletionSummary;
import com.qstory.backend.tutor.dto.AcceptTutorInviteRequest;
import com.qstory.backend.tutor.dto.CreateTutorInviteRequest;
import com.qstory.backend.tutor.dto.CreateTutorScheduleRequest;
import com.qstory.backend.tutor.dto.CreateTutorStudentRequest;
import com.qstory.backend.tutor.dto.TutorInvitePreviewResponse;
import com.qstory.backend.tutor.dto.TutorInviteResponse;
import com.qstory.backend.tutor.dto.TutorReportSummary;
import com.qstory.backend.tutor.dto.TutorScheduleResponse;
import com.qstory.backend.tutor.dto.TutorStudentResponse;
import com.qstory.backend.tutor.service.TutorReportService;
import com.qstory.backend.tutor.service.TutorStudentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tutors", description = "Tutor student roster, schedules, parent invites, and shared reports")
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

    @Operation(summary = "Register a student", description = "TUTOR only. Provisional until the parent accepts an invite.")
    @PostMapping("/v1/tutor-students")
    @ResponseStatus(HttpStatus.CREATED)
    public TutorStudentResponse createStudent(@RequestBody CreateTutorStudentRequest request) {
        return service.createStudent(currentUserResolver.requireRole(Role.TUTOR), request);
    }

    @Operation(summary = "List the caller's own students", description = "TUTOR only.")
    @GetMapping("/v1/tutor-students")
    public List<TutorStudentResponse> listStudents() {
        return service.listStudents(currentUserResolver.requireRole(Role.TUTOR));
    }

    @Operation(summary = "Add a weekly recurring schedule for a student", description = "TUTOR only, must own the student.")
    @PostMapping("/v1/tutor-students/{studentId}/schedule")
    @ResponseStatus(HttpStatus.CREATED)
    public TutorScheduleResponse createSchedule(@PathVariable UUID studentId, @RequestBody CreateTutorScheduleRequest request) {
        return service.createSchedule(currentUserResolver.requireRole(Role.TUTOR), studentId, request);
    }

    @Operation(summary = "Create a single-use parent invite for a student", description = "TUTOR only, must own the student.")
    @PostMapping("/v1/tutor-students/{studentId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public TutorInviteResponse createInvite(@PathVariable UUID studentId, @RequestBody CreateTutorInviteRequest request) {
        return service.createInvite(currentUserResolver.requireRole(Role.TUTOR), studentId, request);
    }

    @Operation(summary = "Preview a tutor's parent invite", description = "No authentication required. Does not consume the invite.")
    @GetMapping("/v1/tutor-invites/{token}")
    public TutorInvitePreviewResponse previewInvite(@PathVariable String token) {
        return service.previewInvite(token);
    }

    @Operation(summary = "Accept a tutor's parent invite",
            description = "Works both signed out (creates a new PARENT account from email/password/displayName, "
                    + "same as ClassController.join) and signed in as an existing PARENT (links the caller's own "
                    + "account, ignoring the signup fields).")
    @PostMapping("/v1/tutor-invites/{token}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse acceptInvite(@PathVariable String token, @RequestBody AcceptTutorInviteRequest request) {
        return service.acceptInvite(currentUserResolver.current(), token, request);
    }

    @Operation(summary = "List all of the caller's students' schedules", description = "TUTOR only.")
    @GetMapping("/v1/tutor-schedules")
    public List<TutorScheduleResponse> listSchedules() {
        return service.listSchedules(currentUserResolver.requireRole(Role.TUTOR));
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
