package com.qstory.backend.org.joinrequest;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.joinrequest.JoinRequestDtos.CreateJoinRequest;
import com.qstory.backend.org.joinrequest.JoinRequestDtos.OrganizationJoinRequestResponse;
import com.qstory.backend.org.joinrequest.JoinRequestDtos.TutorJoinRequestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organization join requests", description = "선생님이 기관에 소속을 신청하고 원장이 수락·거절한다")
@RestController
public class OrganizationJoinRequestController {

    private final OrganizationJoinRequestService service;
    private final CurrentUserResolver currentUserResolver;

    public OrganizationJoinRequestController(
            OrganizationJoinRequestService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Request to join an organization",
            description = "TUTOR only. The organization must be linked to a public institution (found by search). "
                    + "Returns the existing pending request if there is one; 409 ALREADY_ORGANIZATION_MEMBER if linked.")
    @PostMapping("/v1/organization-join-requests")
    public TutorJoinRequestResponse create(@RequestBody CreateJoinRequest request) {
        return service.create(currentUserResolver.requireRole(Role.TUTOR), request.organizationId());
    }

    @Operation(summary = "List my join requests (latest 20)", description = "TUTOR only.")
    @GetMapping("/v1/tutors/me/organization-join-requests")
    public List<TutorJoinRequestResponse> listMine() {
        return service.listMine(currentUserResolver.requireRole(Role.TUTOR));
    }

    @Operation(summary = "Cancel my pending join request", description = "TUTOR only. Idempotent.")
    @PostMapping("/v1/organization-join-requests/{requestId}/cancel")
    public TutorJoinRequestResponse cancel(@PathVariable UUID requestId) {
        return service.cancel(currentUserResolver.requireRole(Role.TUTOR), requestId);
    }

    @Operation(summary = "List pending join requests", description = "DIRECTOR of that organization only.")
    @GetMapping("/v1/organizations/{organizationId}/join-requests")
    public List<OrganizationJoinRequestResponse> listPending(@PathVariable UUID organizationId) {
        return service.listPending(currentUserResolver.requireRole(Role.DIRECTOR), organizationId);
    }

    @Operation(summary = "Accept a join request",
            description = "DIRECTOR of that organization only. Links the tutor like an accepted invite.")
    @PostMapping("/v1/organizations/{organizationId}/join-requests/{requestId}/accept")
    public OrganizationJoinRequestResponse accept(@PathVariable UUID organizationId, @PathVariable UUID requestId) {
        return service.accept(currentUserResolver.requireRole(Role.DIRECTOR), organizationId, requestId);
    }

    @Operation(summary = "Reject a join request", description = "DIRECTOR of that organization only.")
    @PostMapping("/v1/organizations/{organizationId}/join-requests/{requestId}/reject")
    public OrganizationJoinRequestResponse reject(@PathVariable UUID organizationId, @PathVariable UUID requestId) {
        return service.reject(currentUserResolver.requireRole(Role.DIRECTOR), organizationId, requestId);
    }
}
