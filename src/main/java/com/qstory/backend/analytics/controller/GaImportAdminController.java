package com.qstory.backend.analytics.controller;

import com.qstory.backend.analytics.service.GaImportService;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GA 가져오기 수동 실행(첫 백필·재수집 확인용). 정기 실행(GaImportScheduler)과 같은 경로를 탄다. STAFF만.
 * 결과 행 수는 grafana.ga_import_runs(ga_import_run)에 남는다.
 */
@Tag(name = "GA import (internal)", description = "STAFF-only manual GA4 daily import")
@RestController
public class GaImportAdminController {

    static final int MAX_DAYS = 90;

    private final GaImportService service;
    private final CurrentUserResolver currentUserResolver;

    public GaImportAdminController(GaImportService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(
            summary = "Run the GA4 daily import now",
            description = "STAFF only. Re-imports the last `days` days (today included, Asia/Seoul, 1-90, default 3). "
                    + "ok=false with reason=disabled when the key/property is missing or the kill switch is off; "
                    + "ok=false otherwise when GA failed or another run was in progress (see ga_import_run).")
    @PostMapping("/v1/admin/ga-import")
    public Map<String, Object> run(@RequestParam(value = "days", defaultValue = "3") int days) {
        currentUserResolver.requireRole(Role.STAFF);
        if (days < 1 || days > MAX_DAYS) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "days must be 1-" + MAX_DAYS);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (!service.enabled()) {
            body.put("ok", false);
            body.put("reason", "disabled");
            return body;
        }
        LocalDate end = service.today();
        LocalDate start = end.minusDays(days - 1L);
        body.put("ok", service.importRange(start, end));
        body.put("start", start.toString());
        body.put("end", end.toString());
        return body;
    }
}
