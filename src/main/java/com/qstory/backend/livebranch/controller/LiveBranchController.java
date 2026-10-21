package com.qstory.backend.livebranch.controller;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.livebranch.entity.LiveBranchJob;
import com.qstory.backend.livebranch.repository.LiveBranchJobRepository;
import com.qstory.backend.livebranch.service.LiveBranchGenerationService;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.story.service.StoryRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프런트엔드가 RouteDecision.liveBranchJobId로 받은 작업 id를 폴링하는 표면
 * (QuestionPipelineService/LiveBranchGenerationService 참고). 다른 질문 파이프라인 엔드포인트와
 * 마찬가지로 아이 대상 공개 엔드포인트라 별도 역할 게이트가 없다.
 */
@Tag(name = "Live branch", description = "Poll the status of a real-time live-branch generation job")
@RestController
@RequestMapping("/v1/live-branch")
public class LiveBranchController {

    private final LiveBranchJobRepository repository;
    private final StoryRegistryService storyRegistryService;
    private final LiveBranchGenerationService generationService;
    private final CurrentUserResolver currentUserResolver;

    public LiveBranchController(
            LiveBranchJobRepository repository, StoryRegistryService storyRegistryService,
            LiveBranchGenerationService generationService, CurrentUserResolver currentUserResolver) {
        this.repository = repository;
        this.storyRegistryService = storyRegistryService;
        this.generationService = generationService;
        this.currentUserResolver = currentUserResolver;
    }

    /** 그레텔 대화에서 아이가 낸 새 계획(NEW_BRANCH)을 수락했을 때 - 그 말로 실시간 분기를 만든다. */
    public record EnqueueRequest(
            String storyId, String sceneId, String anchorId, String transcript, UUID sessionId, Integer questionRound) {}

    @Operation(
            summary = "Start a live-branch generation job from the companion dialogue",
            description = "The child proposed a workable plan in the Gretel dialogue (replyKind NEW_BRANCH) and accepted "
                    + "'그럼 그렇게 해 볼까?'. Queues the same generation as an uncovered question would and returns "
                    + "{jobId} to poll with GET /v1/live-branch/{jobId}. jobId is null (with reason) when the anchor has "
                    + "live generation off or has reached its cap - the app then simply returns to the story.")
    @PostMapping
    public Map<String, Object> enqueue(@RequestBody EnqueueRequest request) {
        String transcript = request.transcript() == null ? "" : request.transcript().trim();
        if (transcript.isEmpty() || transcript.length() > 300) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "아이가 한 말이 비어 있거나 너무 길어요.");
        }
        var context = storyRegistryService.resolveStoryQuestionContext(
                request.storyId(), request.sceneId(), request.anchorId(),
                request.questionRound() == null ? 1 : request.questionRound(), "text/plain", List.of(), false,
                currentUserResolver.currentOrNull());
        String jobId = generationService.enqueue(
                context.storyContext(), transcript, context.questionRound(), request.sessionId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        if (jobId == null) {
            body.put("reason", context.storyContext().liveBranchGeneration() ? "CAPPED" : "DISABLED");
        }
        return body;
    }

    @Operation(
            summary = "Poll a live-branch generation job",
            description = "Returns {status, options?, errorCode?}. status is one of "
                    + "QUEUED/GENERATING/READY/FAILED. options is only present once READY - a list of exactly 3 "
                    + "(when successful) {familyId, label, meaning} entries mixing newly-generated and existing "
                    + "families; errorCode only once FAILED. On READY the caller should re-fetch "
                    + "GET /v1/stories/{storyId}/content, then build a THREE_PATHS plan client-side from these "
                    + "3 family ids (Phase 2 §3 - no dedicated auto-play any more, the child picks like any other "
                    + "THREE_PATHS route).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current job status"),
            @ApiResponse(responseCode = "404", description = "Unknown job id")
    })
    @GetMapping("/{jobId}")
    public Map<String, Object> get(
            @Parameter(description = "live_branch_job.id") @PathVariable UUID jobId) {
        LiveBranchJob job = repository.findById(jobId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "그 작업을 찾지 못했어요."));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", job.getStatus().name());
        if (job.getResultOptions() != null) {
            body.put("options", job.getResultOptions());
        }
        if (job.getErrorCode() != null) {
            body.put("errorCode", job.getErrorCode());
        }
        return body;
    }
}
