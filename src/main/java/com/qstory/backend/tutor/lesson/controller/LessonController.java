package com.qstory.backend.tutor.lesson.controller;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.dto.CreateLessonRequest;
import com.qstory.backend.tutor.lesson.dto.LessonResponse;
import com.qstory.backend.tutor.lesson.dto.UpdateLessonRequest;
import com.qstory.backend.tutor.lesson.service.LessonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tutor lessons", description = "방문 선생님이 학생/이야기/일정을 묶어 관리하는 수업")
@RestController
public class LessonController {

    private final LessonService service;
    private final CurrentUserResolver currentUserResolver;

    public LessonController(LessonService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List my lessons",
            description = "TUTOR only. status query parameter로 예정/진행중/완료 필터링 가능.")
    @GetMapping("/v1/tutor-lessons")
    public List<LessonResponse> list(@RequestParam(required = false) String status) {
        LessonStatus filter = parseStatus(status);
        return service.listMine(currentUserResolver.requireRole(Role.TUTOR), filter);
    }

    @Operation(summary = "Get a lesson", description = "TUTOR only. Must own the lesson.")
    @GetMapping("/v1/tutor-lessons/{lessonId}")
    public LessonResponse get(@PathVariable UUID lessonId) {
        return service.get(currentUserResolver.requireRole(Role.TUTOR), lessonId);
    }

    @Operation(summary = "Create a lesson", description = "TUTOR only.")
    @PostMapping("/v1/tutor-lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public LessonResponse create(@RequestBody CreateLessonRequest request) {
        return service.create(currentUserResolver.requireRole(Role.TUTOR), request);
    }

    @Operation(summary = "Update a lesson", description = "TUTOR only. Partial update - null 필드는 그대로 둠.")
    @PatchMapping("/v1/tutor-lessons/{lessonId}")
    public LessonResponse update(@PathVariable UUID lessonId, @RequestBody UpdateLessonRequest request) {
        return service.update(currentUserResolver.requireRole(Role.TUTOR), lessonId, request);
    }

    @Operation(summary = "Start a lesson (SCHEDULED → IN_PROGRESS)", description = "TUTOR only.")
    @PostMapping("/v1/tutor-lessons/{lessonId}/start")
    public LessonResponse start(@PathVariable UUID lessonId) {
        return service.start(currentUserResolver.requireRole(Role.TUTOR), lessonId);
    }

    @Operation(summary = "Complete a lesson (→ COMPLETED)", description = "TUTOR only.")
    @PostMapping("/v1/tutor-lessons/{lessonId}/complete")
    public LessonResponse complete(@PathVariable UUID lessonId) {
        return service.complete(currentUserResolver.requireRole(Role.TUTOR), lessonId);
    }

    @Operation(summary = "Delete a lesson", description = "TUTOR only. Idempotent.")
    @DeleteMapping("/v1/tutor-lessons/{lessonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID lessonId) {
        service.delete(currentUserResolver.requireRole(Role.TUTOR), lessonId);
    }

    private static LessonStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LessonStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED,
                    "status는 SCHEDULED/IN_PROGRESS/COMPLETED 중 하나여야 해요.");
        }
    }
}
