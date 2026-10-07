package com.qstory.backend.identity.controller;

import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.AdminTokenGuard;
import com.qstory.backend.identity.service.LegacyDeletedAccountCleanupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 관리자용: 실제 삭제·익명화가 생기기 전에 탈퇴한 계정을 한 번 정리한다. 앱에서 부르지 않으며
 * X-Admin-Token 공유 비밀로 보호된다(AdminTokenGuard). 되돌릴 수 없으니 dryRun=true로 대상 수부터 확인한다.
 */
@Tag(name = "Account admin", description = "Internal one-off maintenance, guarded by a shared-secret header - not part of the app API")
@RestController
public class AccountAdminController {

    private final AdminTokenGuard adminTokenGuard;
    private final LegacyDeletedAccountCleanupService cleanupService;

    public AccountAdminController(AdminTokenGuard adminTokenGuard, LegacyDeletedAccountCleanupService cleanupService) {
        this.adminTokenGuard = adminTokenGuard;
        this.cleanupService = cleanupService;
    }

    @Operation(summary = "Erase accounts deleted before real erasure existed",
            description = "For every app_user with deleted_at set whose email is not yet an @deleted.invalid address, "
                    + "runs the same erasure as account deletion (owned data deleted, account anonymized, original "
                    + "deleted_at kept), one transaction per account, continuing past failures. IRREVERSIBLE. "
                    + "dryRun=true only counts the matching accounts and changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Counts",
                    content = @Content(schema = @Schema(example = "{\"ok\":true,\"dryRun\":false,\"found\":3,\"erased\":3,\"failed\":0}"))),
            @ApiResponse(responseCode = "403", description = "Missing/incorrect X-Admin-Token",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "500", description = "qstory.admin.story-import-token is not configured on this instance",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping("/v1/admin/accounts/erase-legacy-deleted")
    public Map<String, Object> eraseLegacyDeleted(
            @Parameter(in = ParameterIn.HEADER, name = "X-Admin-Token", required = true,
                    description = "Shared secret, must equal qstory.admin.story-import-token") HttpServletRequest request,
            @Parameter(description = "true: only count the matching accounts, change nothing")
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {
        adminTokenGuard.require(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("dryRun", dryRun);
        if (dryRun) {
            body.put("found", cleanupService.countPending());
            return body;
        }
        LegacyDeletedAccountCleanupService.Result result = cleanupService.run();
        body.put("found", result.found());
        body.put("erased", result.erased());
        body.put("failed", result.failed());
        return body;
    }
}
