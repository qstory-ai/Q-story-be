package com.qstory.backend.storyadmin.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.storyadmin.dto.RevisionView;
import com.qstory.backend.storyadmin.dto.RevertRequest;
import com.qstory.backend.storyadmin.dto.SceneEditRequest;
import com.qstory.backend.storyadmin.dto.SceneView;
import com.qstory.backend.storyadmin.dto.SegmentEditRequest;
import com.qstory.backend.storyadmin.dto.SegmentView;
import com.qstory.backend.storyadmin.service.StoryAuthoringService;
import com.qstory.backend.storyadmin.service.StoryRevisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Editing endpoints for story content held in the database.
 *
 * <p>Distinct from StoryImportController, which replaces a whole story from the content pipeline.
 * These are the per-piece writes an authoring tool needs, and every one of them appends to the
 * story's revision history.
 */
@Tag(name = "Story authoring", description = "Per-piece story edits with revision history")
@RestController
public class StoryAuthoringController {

    private final StoryAuthoringService authoringService;
    private final StoryRevisionService revisionService;
    private final CurrentUserResolver currentUserResolver;

    public StoryAuthoringController(
            StoryAuthoringService authoringService,
            StoryRevisionService revisionService,
            CurrentUserResolver currentUserResolver) {
        this.authoringService = authoringService;
        this.revisionService = revisionService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List a story's scenes with the revision to edit against")
    @GetMapping("/v1/admin/stories/{storyId}/scenes")
    public Map<String, Object> scenes(@PathVariable String storyId) {
        currentUserResolver.requireRole(Role.DIRECTOR);
        return Map.of(
                "revision", revisionService.currentRevision(storyId),
                "scenes", authoringService.scenes(storyId));
    }

    @Operation(summary = "Edit one scene",
            description = "baseRevision must match the story's current revision or the write is rejected with 409.")
    @PatchMapping("/v1/admin/stories/{storyId}/scenes/{sceneId}")
    public SceneView editScene(
            @PathVariable String storyId,
            @PathVariable String sceneId,
            @RequestBody SceneEditRequest request) {
        CurrentUser caller = currentUserResolver.requireRole(Role.DIRECTOR);
        return authoringService.editScene(storyId, sceneId, request, caller.userId());
    }

    @Operation(summary = "List one scene's segments")
    @GetMapping("/v1/admin/stories/{storyId}/scenes/{sceneId}/segments")
    public Map<String, Object> segments(@PathVariable String storyId, @PathVariable String sceneId) {
        currentUserResolver.requireRole(Role.DIRECTOR);
        return Map.of(
                "revision", revisionService.currentRevision(storyId),
                "segments", authoringService.segments(storyId, sceneId));
    }

    @Operation(summary = "Edit one segment's payload",
            description = "Editing an utterance's text marks its pre-rendered narration stale.")
    @PatchMapping("/v1/admin/stories/{storyId}/segments/{segmentId}")
    public SegmentView editSegment(
            @PathVariable String storyId,
            @PathVariable UUID segmentId,
            @RequestBody SegmentEditRequest request) {
        CurrentUser caller = currentUserResolver.requireRole(Role.DIRECTOR);
        return authoringService.editSegment(storyId, segmentId, request, caller.userId());
    }

    @Operation(summary = "Undo one revision",
            description = "Restores what that revision changed and records the undo as a new revision.")
    @PostMapping("/v1/admin/stories/{storyId}/revisions/revert")
    public Map<String, Object> revert(@PathVariable String storyId, @RequestBody RevertRequest request) {
        CurrentUser caller = currentUserResolver.requireRole(Role.DIRECTOR);
        return authoringService.revert(storyId, request, caller.userId());
    }

    @Operation(summary = "Read a story's edit history, newest first")
    @GetMapping("/v1/admin/stories/{storyId}/revisions")
    public List<RevisionView> revisions(@PathVariable String storyId) {
        currentUserResolver.requireRole(Role.DIRECTOR);
        return revisionService.history(storyId).stream().map(RevisionView::of).toList();
    }
}
