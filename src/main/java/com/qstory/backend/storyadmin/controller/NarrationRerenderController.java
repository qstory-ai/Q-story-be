package com.qstory.backend.storyadmin.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.storyadmin.service.NarrationRerenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Re-recording the narration for lines whose script has been edited in the database. */
@Tag(name = "Narration re-render", description = "Bring pre-rendered narration back in step with edited script")
@RestController
public class NarrationRerenderController {

    private final NarrationRerenderService rerenderService;
    private final CurrentUserResolver currentUserResolver;

    public NarrationRerenderController(
            NarrationRerenderService rerenderService, CurrentUserResolver currentUserResolver) {
        this.rerenderService = rerenderService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List lines whose recording no longer matches the script")
    @GetMapping("/v1/admin/stories/{storyId}/narration/stale")
    public List<NarrationRerenderService.StaleLine> stale(@PathVariable String storyId) {
        currentUserResolver.requireRole(Role.DIRECTOR);
        return rerenderService.staleLines(storyId);
    }

    @Operation(summary = "Re-record one line",
            description = "Synthesizes the current script with the speaker's cast voice, stores it, and points the clip at it.")
    @PostMapping("/v1/admin/stories/{storyId}/segments/{segmentId}/narration/rerender")
    public Map<String, Object> rerender(@PathVariable String storyId, @PathVariable UUID segmentId) {
        CurrentUser caller = currentUserResolver.requireRole(Role.DIRECTOR);
        return rerenderService.rerender(storyId, segmentId, caller.userId());
    }
}
