package com.qstory.backend.tutor.lessonplan.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.tutor.lessonplan.dto.CreateTutorLessonPlanRequest;
import com.qstory.backend.tutor.lessonplan.dto.TutorLessonPlanResponse;
import com.qstory.backend.tutor.lessonplan.service.TutorLessonPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tutor lesson plans", description = "Tutor's per-student '수업에 사용할 이야기' shelf")
@RestController
public class TutorLessonPlanController {

    private final TutorLessonPlanService service;
    private final CurrentUserResolver currentUserResolver;

    public TutorLessonPlanController(TutorLessonPlanService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List all lesson plans across my students", description = "TUTOR only. Newest first.")
    @GetMapping("/v1/tutor-lesson-plans")
    public List<TutorLessonPlanResponse> listMine() {
        return service.listMine(currentUserResolver.requireRole(Role.TUTOR));
    }

    @Operation(summary = "List a student's lesson plans", description = "TUTOR only. Must own the student.")
    @GetMapping("/v1/tutor-students/{studentId}/lesson-plans")
    public List<TutorLessonPlanResponse> listForStudent(@PathVariable UUID studentId) {
        return service.listForStudent(currentUserResolver.requireRole(Role.TUTOR), studentId);
    }

    @Operation(summary = "Add a story to a student's plan",
            description = "TUTOR only. Idempotent - re-adding the same (student, story) returns the existing plan.")
    @PostMapping("/v1/tutor-lesson-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public TutorLessonPlanResponse create(@RequestBody CreateTutorLessonPlanRequest request) {
        return service.create(currentUserResolver.requireRole(Role.TUTOR), request);
    }

    @Operation(summary = "Remove a lesson plan",
            description = "TUTOR only. Idempotent - deleting a non-existent plan succeeds silently.")
    @DeleteMapping("/v1/tutor-lesson-plans/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID planId) {
        service.delete(currentUserResolver.requireRole(Role.TUTOR), planId);
    }
}
