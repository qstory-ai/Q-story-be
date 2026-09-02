package com.qstory.backend.org.usage.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.org.usage.dto.OrganizationUsageResponse;
import com.qstory.backend.org.usage.service.OrganizationUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organization usage", description = "DIRECTOR 대시보드용 기관 이용 현황 요약")
@RestController
public class OrganizationUsageController {

    private final OrganizationUsageService service;
    private final CurrentUserResolver currentUserResolver;

    public OrganizationUsageController(OrganizationUsageService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Get organization usage summary", description = "DIRECTOR only. 자기 기관만.")
    @GetMapping("/v1/organizations/{organizationId}/usage")
    public OrganizationUsageResponse get(@PathVariable UUID organizationId) {
        return service.read(currentUserResolver.requireRole(Role.DIRECTOR), organizationId);
    }
}
