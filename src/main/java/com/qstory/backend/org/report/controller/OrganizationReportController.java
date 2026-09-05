package com.qstory.backend.org.report.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.report.dto.OrganizationReportResponse;
import com.qstory.backend.org.report.service.OrganizationReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organization reports", description = "Institution-level aggregated learning activity")
@RestController
public class OrganizationReportController {

    private final OrganizationReportService service;
    private final CurrentUserResolver currentUserResolver;

    public OrganizationReportController(OrganizationReportService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Get institution aggregate report", description = "DIRECTOR only. Aggregates activity by class and story without exposing individual question text.")
    @GetMapping("/v1/organizations/{organizationId}/reports")
    public OrganizationReportResponse get(@PathVariable UUID organizationId) {
        return service.read(currentUserResolver.requireRole(Role.DIRECTOR), organizationId);
    }
}
