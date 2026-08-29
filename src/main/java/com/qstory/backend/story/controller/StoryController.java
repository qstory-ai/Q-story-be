package com.qstory.backend.story.controller;
import com.qstory.backend.story.service.StoryCatalogService;
import com.qstory.backend.story.service.StoryContentAssemblyService;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.story.dto.StoryCatalogEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 공개 작품 카탈로그 - 메타데이터만 제공하며, 실제 진행 콘텐츠는 포함하지 않는다 (StoryCatalogEntry 참고). */
@Tag(name = "Stories", description = "Story catalog metadata and content")
@RestController
@RequestMapping("/v1/stories")
public class StoryController {

    private final StoryCatalogService catalogService;
    private final StoryContentAssemblyService contentAssemblyService;
    private final CurrentUserResolver currentUserResolver;

    public StoryController(
            StoryCatalogService catalogService, StoryContentAssemblyService contentAssemblyService,
            CurrentUserResolver currentUserResolver) {
        this.catalogService = catalogService;
        this.contentAssemblyService = contentAssemblyService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(
            summary = "List available stories",
            description = "Returns every story whose availability is BETA or PUBLISHED (RETIRED and unregistered "
                    + "stories are omitted). No authentication required.")
    @ApiResponse(responseCode = "200", description = "Catalog entries, possibly empty",
            content = @Content(schema = @Schema(implementation = StoryCatalogEntry.class)))
    @GetMapping
    public List<StoryCatalogEntry> list() {
        return catalogService.list();
    }

    @Operation(
            summary = "Get a single story by its id",
            description = "Fails the same way for a story that was never registered and one that has been "
                    + "RETIRED, so a caller can't distinguish \"never existed\" from \"no longer offered\".")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Story found and currently offered",
                    content = @Content(schema = @Schema(implementation = StoryCatalogEntry.class))),
            @ApiResponse(responseCode = "402", description = "Story requires an entitlement the caller's organization doesn't have",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "404", description = "Unknown story id, or the story is RETIRED",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @GetMapping("/{storyId}")
    public StoryCatalogEntry get(
            @Parameter(description = "Stable content id, e.g. \"HG\"", example = "HG") @PathVariable String storyId) {
        return catalogService.get(storyId, currentUserResolver.currentOrNull());
    }

    @Operation(
            summary = "Get a story's full narrative content",
            description = "Returns the same {generatedContent, packageData} shape fe/q-story-web's own build "
                    + "pipeline used to bundle statically - the frontend's buildStoryRuntimePackage() consumes "
                    + "this response with no reshaping. 404s if the story is unregistered/RETIRED (see GET "
                    + "/{storyId}) or if it has never been imported via POST /v1/admin/stories/import.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Full generatedContent + packageData for the story"),
            @ApiResponse(responseCode = "402", description = "Story requires an entitlement the caller's organization doesn't have",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "404", description = "Unknown/RETIRED story id, or not yet imported",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @GetMapping("/{storyId}/content")
    public ObjectNode content(
            @Parameter(description = "Stable content id, e.g. \"HG\"", example = "HG") @PathVariable String storyId) {
        // content에 손대기 전에 404/402를 발생시킨다 (retired/미등록/entitlement 규칙도 여기서 강제된다)
        catalogService.get(storyId, currentUserResolver.currentOrNull());
        ObjectNode content = contentAssemblyService.get(storyId);
        if (content == null) {
            throw ApiException.contractError(ErrorCode.NOT_FOUND, "이 작품은 아직 콘텐츠가 준비되지 않았어요.");
        }
        return content;
    }
}
