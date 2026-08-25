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

/**
 * 데이터베이스에서 대본이 수정된 대사의 내레이션을 다시 녹음한다.
 *
 * <p>Role.STAFF 권한으로 제한된다(StoryAuthoringController의 클래스 문서 참고) - 재녹음이 한 번
 * 일어날 때마다 유료 외부 TTS 호출이 발생하므로, 셀프 가입한 고객 역할이 절대 접근할 수 없어야 한다.
 */
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
        currentUserResolver.requireRole(Role.STAFF);
        return rerenderService.staleLines(storyId);
    }

    @Operation(summary = "Re-record one line",
            description = "Synthesizes the current script with the speaker's cast voice, stores it, and points the clip at it.")
    @PostMapping("/v1/admin/stories/{storyId}/segments/{segmentId}/narration/rerender")
    public Map<String, Object> rerender(@PathVariable String storyId, @PathVariable UUID segmentId) {
        CurrentUser caller = currentUserResolver.requireRole(Role.STAFF);
        return rerenderService.rerender(storyId, segmentId, caller.userId());
    }
}
