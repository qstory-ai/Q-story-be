package com.qstory.backend.storyadmin.controller;
import com.qstory.backend.storyadmin.service.StoryImportService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.EdgeErrorCode;
import com.qstory.backend.common.error.EdgeException;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.DigestUtil;
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
 * Internal admin surface: pushes the compiled output of fe/q-story-web's
 * generate-story-package.mjs into this backend's DB (see StoryImportService). Not part of the
 * child-facing API - guarded by a shared-secret header, the same posture as
 * qstory.supabase.voice-research-cleanup-token.
 */
@Tag(name = "Story admin", description = "Internal content-authoring surface, guarded by a shared-secret header - not part of the child-facing API")
@RestController
public class StoryImportController {

    private static final long MAX_IMPORT_PAYLOAD_BYTES = 4L * 1024 * 1024;

    private final AppProperties config;
    private final ObjectMapper objectMapper;
    private final StoryImportService service;

    public StoryImportController(AppProperties config, ObjectMapper objectMapper, StoryImportService service) {
        this.config = config;
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
                            + "\"scenesImported\":9,\"segmentsImported\":120,\"fallbacksImported\":14,"
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
        requireAdminToken(request);
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper, MAX_IMPORT_PAYLOAD_BYTES);
        StoryImportService.ImportResult result = service.importStory(body);
        HttpJsonWriter.writeJson(response, objectMapper, 200, Map.of(
                "ok", true,
                "storyId", result.storyId(),
                "scenesImported", result.scenesImported(),
                "segmentsImported", result.segmentsImported(),
                "fallbacksImported", result.fallbacksImported(),
                "fallbackSegmentsImported", result.fallbackSegmentsImported()));
    }

    private void requireAdminToken(HttpServletRequest request) {
        if (!config.admin().storyImportTokenConfigured()) {
            throw new EdgeException(EdgeErrorCode.SERVER_NOT_CONFIGURED);
        }
        String provided = request.getHeader("X-Admin-Token");
        if (provided == null || !DigestUtil.constantTimeEquals(config.admin().storyImportToken(), provided)) {
            throw new EdgeException(EdgeErrorCode.ADMIN_UNAUTHORIZED);
        }
    }
}
