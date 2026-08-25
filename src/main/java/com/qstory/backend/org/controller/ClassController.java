package com.qstory.backend.org.controller;
import com.qstory.backend.org.service.ClassService;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.dto.ClassInviteResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.CreateClassRequest;
import com.qstory.backend.org.dto.JoinClassRequest;
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

@Tag(name = "Classes", description = "Classrooms within an organization - the class account, join codes, invites, and parent self-signup")
@RestController
public class ClassController {

    private final ClassService service;
    private final CurrentUserResolver currentUserResolver;

    public ClassController(ClassService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Create a class", description = "DIRECTOR of the organization only. Also creates the class's own CLASS_ACCOUNT login.")
    @PostMapping("/v1/organizations/{orgId}/classes")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassResponse create(@PathVariable UUID orgId, @RequestBody CreateClassRequest request) {
        return service.create(currentUserResolver.requireRole(Role.DIRECTOR), orgId, request);
    }

    @Operation(summary = "List an organization's classes", description = "DIRECTOR of the organization only.")
    @GetMapping("/v1/organizations/{orgId}/classes")
    public List<ClassResponse> list(@PathVariable UUID orgId) {
        return service.list(currentUserResolver.requireRole(Role.DIRECTOR), orgId);
    }

    @Operation(summary = "Get a class", description = "The owning DIRECTOR, or that class's own CLASS_ACCOUNT.")
    @GetMapping("/v1/classes/{classId}")
    public ClassResponse get(@PathVariable UUID classId) {
        return service.get(currentUserResolver.require(), classId);
    }

    @Operation(summary = "Create a single-use invite for this class",
            description = "DIRECTOR only. The returned token is shown once - only its hash is stored.")
    @PostMapping("/v1/classes/{classId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassInviteResponse createInvite(@PathVariable UUID classId) {
        return service.createInvite(currentUserResolver.require(), classId);
    }

    @Operation(summary = "Join a class as a parent (this is parent signup)",
            description = "No authentication required. Provide exactly one of classCode (durable, "
                    + "reusable, printable on a flyer) or inviteToken (single-use, from POST "
                    + "/v1/classes/{classId}/invites) plus email/password/displayName.")
    @PostMapping("/v1/classes/join")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse join(@RequestBody JoinClassRequest request) {
        return service.join(request);
    }
}
