package com.qstory.backend.org.controller;
import com.qstory.backend.org.service.OrganizationService;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.dto.CreateOrganizationRequest;
import com.qstory.backend.org.dto.EntitlementResponse;
import com.qstory.backend.org.dto.OrganizationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organizations", description = "Kindergarten (director-owned) organizations and their entitlement status")
@RestController
public class OrganizationController {

    private final OrganizationService service;
    private final CurrentUserResolver currentUserResolver;

    public OrganizationController(OrganizationService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Create the caller's organization",
            description = "DIRECTOR only, and only once - 409s if the caller already owns one. Returns a fresh "
                    + "token (the caller's prior one has no orgId claim yet) - the client must swap to it.")
    @PostMapping("/v1/organizations")
    public AuthResponse create(@RequestBody CreateOrganizationRequest request) {
        return service.create(currentUserResolver.requireRole(Role.DIRECTOR), request);
    }

    @Operation(summary = "Get an organization", description = "The organization's own DIRECTOR only.")
    @GetMapping("/v1/organizations/{orgId}")
    public OrganizationResponse get(@PathVariable UUID orgId) {
        return service.get(currentUserResolver.requireRole(Role.DIRECTOR), orgId);
    }

    @Operation(summary = "Get an organization's entitlement status", description = "Thin read for a paywall/subscribe-banner UI.")
    @GetMapping("/v1/organizations/{orgId}/entitlement")
    public EntitlementResponse entitlement(@PathVariable UUID orgId) {
        return service.entitlement(currentUserResolver.requireRole(Role.DIRECTOR), orgId);
    }
}
