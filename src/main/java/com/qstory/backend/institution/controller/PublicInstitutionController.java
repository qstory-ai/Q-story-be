package com.qstory.backend.institution.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.institution.dto.PublicInstitutionSearchResponse;
import com.qstory.backend.institution.service.PublicInstitutionSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Public institutions", description = "공공데이터 유치원·어린이집 목록 검색")
@RestController
public class PublicInstitutionController {

    private final PublicInstitutionSearchService service;
    private final CurrentUserResolver currentUserResolver;

    public PublicInstitutionController(PublicInstitutionSearchService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Search public kindergartens/daycares",
            description = "No login needed (director signup uses it). q is required (2+ chars), at most 20 results, "
                    + "no paging. registered/organizationId are filled only for a logged-in DIRECTOR or TUTOR.")
    @GetMapping("/v1/public-institutions")
    public PublicInstitutionSearchResponse search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "sido", required = false) String sido,
            @RequestParam(name = "limit", required = false) Integer limit) {
        CurrentUser caller = currentUserResolver.currentOrNull();
        boolean staff = caller != null && (caller.role() == Role.DIRECTOR || caller.role() == Role.TUTOR);
        return service.search(q, kind, sido, limit, staff);
    }
}
