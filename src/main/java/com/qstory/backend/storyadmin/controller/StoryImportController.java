package com.qstory.backend.storyadmin.controller;
import com.qstory.backend.storyadmin.service.StoryImportService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.AdminTokenGuard;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 관리자용 엔드포인트: fe/q-story-web의 generate-story-package.mjs가 생성한 컴파일 결과물을
 * 이 백엔드의 DB에 반영한다(StoryImportService 참고). 어린이 대상 API의 일부가 아니며,
 * qstory.supabase.voice-research-cleanup-token과 동일한 방식으로 공유 비밀(shared-secret) 헤더로
 * 보호된다.
 */
@Tag(name = "Story admin", description = "Internal content-authoring surface, guarded by a shared-secret header - not part of the child-facing API")
@RestController
public class StoryImportController {

    private static final long MAX_IMPORT_PAYLOAD_BYTES = 4L * 1024 * 1024;

    private final AdminTokenGuard adminTokenGuard;
    private final ObjectMapper objectMapper;
    private final StoryImportService service;

    public StoryImportController(AdminTokenGuard adminTokenGuard, ObjectMapper objectMapper, StoryImportService service) {
        this.adminTokenGuard = adminTokenGuard;
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @Operation(
            summary = "Import a story's compiled narrative content",
            description = "Body is {generatedContent, packageData} - the two JSON files "
                    + "fe/q-story-web's generate-story-package.mjs produces, posted together verbatim by the "
                    + "frontend's scripts/import-story-to-backend.mjs. Every import is a full delete-and-reinsert "
                    + "of the target story's scenes/segments (StoryEntity's own anchors/action-families/cast rows "
                    + "are untouched, except each fallback response's requiresFamilyId/rejoinSlot/rejoinTarget "
                    + "fields, which are updated in place). Reloads the in-memory content cache on success, so "
                    + "GET /v1/stories/{id}/content reflects the new content immediately. Max 4MB.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import counts",
                    content = @Content(schema = @Schema(example = "{\"ok\":true,\"storyId\":\"HG\","
                            + "\"scenesImported\":9,\"segmentsImported\":120,\"fallbacksImported\":14,\"assetsImported\":256,"
                            + "\"fallbackSegmentsImported\":56}"))),
            @ApiResponse(responseCode = "400", description = "Malformed body, or an unknown story/family id referenced",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "401", description = "Missing/incorrect X-Admin-Token",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "413", description = "Body exceeds 4MB",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "500", description = "qstory.admin.story-import-token is not configured on this instance",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "{generatedContent, packageData} - fe/q-story-web's compiled build output",
            required = true)
    @PostMapping("/v1/admin/stories/import")
    public void importStory(
            @Parameter(in = ParameterIn.HEADER, name = "X-Admin-Token", required = true,
                    description = "Shared secret, must equal qstory.admin.story-import-token") HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        adminTokenGuard.require(request);
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper, MAX_IMPORT_PAYLOAD_BYTES);
        StoryImportService.ImportResult result = service.importStory(body);
        HttpJsonWriter.writeJson(response, objectMapper, 200, Map.of(
                "ok", true,
                "storyId", result.storyId(),
                "scenesImported", result.scenesImported(),
                "segmentsImported", result.segmentsImported(),
                "fallbacksImported", result.fallbacksImported(),
                "fallbackSegmentsImported", result.fallbackSegmentsImported(),
                "assetsImported", result.assetsImported()));
    }
}
