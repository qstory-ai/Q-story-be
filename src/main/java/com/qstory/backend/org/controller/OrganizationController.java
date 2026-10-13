package com.qstory.backend.org.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.dto.CreateOrganizationRequest;
import com.qstory.backend.org.dto.EntitlementResponse;
import com.qstory.backend.org.dto.LinkPublicInstitutionRequest;
import com.qstory.backend.org.dto.OrganizationProfileResponse;
import com.qstory.backend.org.dto.GuardianConsentAckRequest;
import com.qstory.backend.org.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
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
                    + "token (the caller's prior one has no orgId claim yet) - the client must swap to it. "
                    + "With publicInstitutionId the organization takes that public kindergarten/daycare's name; "
                    + "409 PUBLIC_INSTITUTION_ALREADY_REGISTERED if another director already registered it.")
    @PostMapping("/v1/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse create(@RequestBody CreateOrganizationRequest request) {
        return service.create(currentUserResolver.requireRole(Role.DIRECTOR), request);
    }

    @Operation(summary = "Acknowledge that the organization collects guardian consent",
            description = "DIRECTOR of this organization. Records that the organization obtains consent from each "
                    + "child's guardian (A5) - used by the re-consent screen for organizations created before 2026-10-v2.")
    @PostMapping("/v1/organizations/{orgId}/guardian-consent-ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledgeGuardianConsent(@PathVariable UUID orgId, @RequestBody GuardianConsentAckRequest request) {
        service.acknowledgeGuardianConsent(currentUserResolver.requireRole(Role.DIRECTOR), orgId, request);
    }

    @Operation(summary = "Get the caller's organization profile", description = "DIRECTOR of that organization only.")
    @GetMapping("/v1/organizations/{orgId}/profile")
    public OrganizationProfileResponse profile(@PathVariable UUID orgId) {
        return service.profile(currentUserResolver.requireRole(Role.DIRECTOR), orgId);
    }

    @Operation(summary = "Link the organization to a public kindergarten/daycare",
            description = "DIRECTOR only. Lets tutors find the organization by search and send a join request. "
                    + "409 PUBLIC_INSTITUTION_ALREADY_REGISTERED if another organization already linked it.")
    @PutMapping("/v1/organizations/{orgId}/public-institution")
    public OrganizationProfileResponse linkPublicInstitution(
            @PathVariable UUID orgId, @RequestBody LinkPublicInstitutionRequest request) {
        return service.linkPublicInstitution(
                currentUserResolver.requireRole(Role.DIRECTOR), orgId, request.publicInstitutionId());
    }

    @Operation(summary = "Get an organization's entitlement status", description = "Thin read for a paywall/subscribe-banner UI.")
    @GetMapping("/v1/organizations/{orgId}/entitlement")
    public EntitlementResponse entitlement(@PathVariable UUID orgId) {
        return service.entitlement(currentUserResolver.requireRole(Role.DIRECTOR), orgId);
    }
}
