package com.qstory.backend.org.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.dto.ClassHomeroomInvitePreviewResponse;
import com.qstory.backend.org.dto.ClassHomeroomInviteResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.service.ClassHomeroomInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Class homeroom invites", description = "반 담임 초대 - 선생님이 코드로 수락하면 기관에 소속되고 그 반의 담임이 된다")
@RestController
public class ClassHomeroomInviteController {

    private final ClassHomeroomInviteService service;
    private final CurrentUserResolver currentUserResolver;

    public ClassHomeroomInviteController(ClassHomeroomInviteService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Issue a homeroom invite for a class",
            description = "Owning DIRECTOR only. Replaces the class's previous unused invite (it stops working). Expires in 14 days, single use.")
    @PostMapping("/v1/classes/{classId}/homeroom-invites")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassHomeroomInviteResponse issue(@PathVariable UUID classId) {
        return service.issue(currentUserResolver.requireRole(Role.DIRECTOR), classId);
    }

    @Operation(summary = "Get the class's active homeroom invite",
            description = "Owning DIRECTOR only. 404 when there is no unused, unexpired invite.")
    @GetMapping("/v1/classes/{classId}/homeroom-invites/current")
    public ClassHomeroomInviteResponse current(@PathVariable UUID classId) {
        return service.current(currentUserResolver.requireRole(Role.DIRECTOR), classId);
    }

    @Operation(summary = "Preview a homeroom invite by its code",
            description = "No authentication required. Case-insensitive. 404 for an unknown code, 410 INVALID_INVITE when expired or used.")
    @GetMapping("/v1/class-homeroom-invites/by-code/{code}")
    public ClassHomeroomInvitePreviewResponse preview(@PathVariable String code) {
        return service.preview(code);
    }

    @Operation(summary = "Accept a homeroom invite by its code",
            description = "Authenticated TUTOR only. Links the tutor to the class's organization if needed (same rules as an "
                    + "organization tutor invite) and makes them the homeroom tutor (same policy as PUT /v1/classes/{classId}/homeroom). "
                    + "404 for an unknown code, 410 INVALID_INVITE when expired or used.")
    @PostMapping("/v1/class-homeroom-invites/by-code/{code}/accept")
    public ClassResponse accept(@PathVariable String code) {
        return service.accept(currentUserResolver.requireRole(Role.TUTOR), code);
    }
}
