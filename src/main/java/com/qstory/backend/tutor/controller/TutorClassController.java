package com.qstory.backend.tutor.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.tutor.dto.CreateTutorClassRequest;
import com.qstory.backend.tutor.service.TutorClassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 선생님 관점의 반 목록/생성. 기관 관리자 관점(ClassController, /v1/organizations/{id}/classes)과 같은 테이블. */
@Tag(name = "TutorClass", description = "Classes a tutor can assign students and lessons to")
@RestController
public class TutorClassController {

    private final TutorClassService service;
    private final CurrentUserResolver currentUserResolver;

    public TutorClassController(TutorClassService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List classes visible to the caller",
            description = "TUTOR only. Classes the tutor created plus classes of every organization the tutor belongs to.")
    @GetMapping("/v1/tutor-classes")
    public List<ClassResponse> list() {
        return service.listVisible(currentUserResolver.requireRole(Role.TUTOR));
    }

    @Operation(summary = "Create a class",
            description = "TUTOR only. Body {name, organizationId?}. With organizationId the tutor must belong to that "
                    + "organization and the class shows up in the organization's class list too.")
    @PostMapping("/v1/tutor-classes")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassResponse create(@RequestBody CreateTutorClassRequest request) {
        return service.create(currentUserResolver.requireRole(Role.TUTOR), request);
    }
}
