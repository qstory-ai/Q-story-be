package com.qstory.backend.org.tutor.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.tutor.dto.OrganizationTutorInvitePreviewResponse;
import com.qstory.backend.org.tutor.dto.OrganizationTutorInviteResponse;
import com.qstory.backend.org.tutor.dto.OrganizationTutorInviteSummary;
import com.qstory.backend.org.tutor.dto.OrganizationTutorResponse;
import com.qstory.backend.org.tutor.dto.TutorOrganizationResponse;
import com.qstory.backend.org.tutor.service.OrganizationTutorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organization tutors", description = "기관이 소속 선생님을 초대하고 관리한다")
@RestController
public class OrganizationTutorController {

    private final OrganizationTutorService service;
    private final CurrentUserResolver currentUserResolver;

    public OrganizationTutorController(OrganizationTutorService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    /* ---------------------------------------------------------- director-side */

    @Operation(summary = "List tutors linked to an organization", description = "DIRECTOR of that organization only.")
    @GetMapping("/v1/organizations/{organizationId}/tutors")
    public List<OrganizationTutorResponse> listOrganizationTutors(@PathVariable UUID organizationId) {
        return service.listOrganizationTutors(currentUserResolver.requireRole(Role.DIRECTOR), organizationId);
    }

    @Operation(summary = "List tutor invites issued by an organization", description = "DIRECTOR only.")
    @GetMapping("/v1/organizations/{organizationId}/tutor-invites")
    public List<OrganizationTutorInviteSummary> listInvites(@PathVariable UUID organizationId) {
        return service.listInvites(currentUserResolver.requireRole(Role.DIRECTOR), organizationId);
    }

    @Operation(summary = "Issue a new tutor invite (link + short code)", description = "DIRECTOR only. Token is returned once.")
    @PostMapping("/v1/organizations/{organizationId}/tutor-invites")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationTutorInviteResponse createInvite(@PathVariable UUID organizationId) {
        return service.createInvite(currentUserResolver.requireRole(Role.DIRECTOR), organizationId);
    }

    @Operation(summary = "Unlink a tutor from an organization", description = "DIRECTOR only. Idempotent.")
    @DeleteMapping("/v1/organizations/{organizationId}/tutors/{tutorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkTutor(@PathVariable UUID organizationId, @PathVariable UUID tutorId) {
        service.unlinkTutor(currentUserResolver.requireRole(Role.DIRECTOR), organizationId, tutorId);
    }

    /* ---------------------------------------------------------- tutor-side */

    @Operation(summary = "Preview an organization tutor invite (by token)",
            description = "No authentication required. Does not consume the invite.")
    @GetMapping("/v1/organization-tutor-invites/{token}")
    public OrganizationTutorInvitePreviewResponse previewInvite(@PathVariable String token) {
        return service.previewInvite(token);
    }

    @Operation(summary = "Preview an organization tutor invite (by short code)",
            description = "No authentication required. Case-insensitive.")
    @GetMapping("/v1/organization-tutor-invites/by-code/{code}")
    public OrganizationTutorInvitePreviewResponse previewInviteByCode(@PathVariable String code) {
        return service.previewInviteByCode(code);
    }

    @Operation(summary = "Accept an organization tutor invite (by token)",
            description = "Authenticated TUTOR only. Idempotent - already-linked tutor keeps the same relation.")
    @PostMapping("/v1/organization-tutor-invites/{token}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationTutorResponse acceptInvite(@PathVariable String token) {
        return service.acceptInvite(currentUserResolver.requireRole(Role.TUTOR), token);
    }

    @Operation(summary = "Accept an organization tutor invite (by short code)",
            description = "Authenticated TUTOR only. Idempotent.")
    @PostMapping("/v1/organization-tutor-invites/by-code/{code}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationTutorResponse acceptInviteByCode(@PathVariable String code) {
        return service.acceptInviteByCode(currentUserResolver.requireRole(Role.TUTOR), code);
    }

    /* ---------------------------------------------------------- tutor-side self-listing */

    @Operation(summary = "List organizations I belong to as a tutor", description = "TUTOR only.")
    @GetMapping("/v1/tutors/me/organizations")
    public List<TutorOrganizationResponse> listMyOrganizations() {
        // 소속 조회는 caller.userId() 스코프로만 - 다른 사람의 소속을 볼 방법이 아예 없다.
        return service.listMyOrganizations(currentUserResolver.requireRole(Role.TUTOR));
    }
}
