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
 * 데이터베이스에 저장된 스토리 콘텐츠를 수정하는 엔드포인트.
 *
 * <p>콘텐츠 파이프라인으로부터 스토리 전체를 교체하는 StoryImportController와는 구분된다.
 * 이것들은 저작 도구가 필요로 하는 조각 단위 쓰기 작업이며, 그중 어느 것을 호출하든 스토리의
 * 리비전 이력에 기록이 추가된다.
 *
 * <p>Role.DIRECTOR가 아니라 Role.STAFF 권한으로 제한된다 - DIRECTOR는 공개 가입을 통해 얻을 수
 * 있는 셀프서비스 고객 역할이며, 예전에 이 자리에서 DIRECTOR를 재사용했을 때는 가입한 고객이라면
 * 누구나 아무 스토리든 수정/되돌리기(revert)할 수 있었다. STAFF는 AuthController의 관리자
 * 토큰으로 보호되는 경로를 통해서만 발급될 수 있다.
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
        currentUserResolver.requireRole(Role.STAFF);
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
        CurrentUser caller = currentUserResolver.requireRole(Role.STAFF);
        return authoringService.editScene(storyId, sceneId, request, caller.userId());
    }

    @Operation(summary = "List one scene's segments")
    @GetMapping("/v1/admin/stories/{storyId}/scenes/{sceneId}/segments")
    public Map<String, Object> segments(@PathVariable String storyId, @PathVariable String sceneId) {
        currentUserResolver.requireRole(Role.STAFF);
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
        CurrentUser caller = currentUserResolver.requireRole(Role.STAFF);
        return authoringService.editSegment(storyId, segmentId, request, caller.userId());
    }

    @Operation(summary = "Undo one revision",
            description = "Restores what that revision changed and records the undo as a new revision.")
    @PostMapping("/v1/admin/stories/{storyId}/revisions/revert")
    public Map<String, Object> revert(@PathVariable String storyId, @RequestBody RevertRequest request) {
        CurrentUser caller = currentUserResolver.requireRole(Role.STAFF);
        return authoringService.revert(storyId, request, caller.userId());
    }

    @Operation(summary = "Read a story's edit history, newest first")
    @GetMapping("/v1/admin/stories/{storyId}/revisions")
    public List<RevisionView> revisions(@PathVariable String storyId) {
        currentUserResolver.requireRole(Role.STAFF);
        return revisionService.history(storyId).stream().map(RevisionView::of).toList();
    }
}
