package com.qstory.backend.shadow.controller;
import com.qstory.backend.shadow.service.ShadowReviewService;

import com.qstory.backend.shadow.entity.ShadowIntentCandidate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A human review surface for shadow_intent_candidates - this content is never shown to a child
 * directly (see ShadowIntentCollectionService); a reviewer approves/rejects here before any of it
 * can inform authored content. Drafting the actual replacement story content (calling an LLM/image
 * model to write a candidate family) is intentionally out of scope, mirroring the original repo,
 * where shadow-generation.mjs was a template/validation library with no wired-up caller either.
 */
@Tag(name = "Shadow review", description = "Internal reviewer surface for shadow_intent_candidates - normalized, repeated child intents the current content doesn't cover, collected from telemetry")
@RestController
@RequestMapping("/v1/shadow/candidates")
public class ShadowReviewController {

    private final ShadowReviewService service;

    public ShadowReviewController(ShadowReviewService service) {
        this.service = service;
    }

    @Operation(
            summary = "List candidates ready for review",
            description = "Candidates advance to READY_FOR_REVIEW automatically once distinctSessionCount "
                    + "crosses the promotion threshold (see ShadowIntentCollectionService) - this endpoint never "
                    + "returns candidates still COLLECTING, or already APPROVED/REJECTED. Sorted by "
                    + "occurrenceCount desc, then lastSeenAt desc.")
    @ApiResponse(responseCode = "200", description = "Candidates, possibly empty, most-frequent first")
    @GetMapping("/ready-for-review")
    public List<ShadowIntentCandidate> readyForReview() {
        return service.readyForReview();
    }

    public record ReviewDecision(String reviewNote) {}

    @Operation(
            summary = "Approve a candidate",
            description = "Marks the candidate APPROVED. Never triggers content generation itself - a human "
                    + "still authors the replacement content elsewhere.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated candidate"),
            @ApiResponse(responseCode = "404", description = "Unknown candidate id")
    })
    @PostMapping("/{candidateId}/approve")
    public ShadowIntentCandidate approve(
            @Parameter(description = "shadow_intent_candidates.id") @PathVariable UUID candidateId,
            @Parameter(description = "Optional reviewer note, max 1000 chars")
            @org.springframework.web.bind.annotation.RequestBody(required = false) ReviewDecision decision) {
        return service.approve(candidateId, decision == null ? null : decision.reviewNote());
    }

    @Operation(summary = "Reject a candidate", description = "Marks the candidate REJECTED - it will not be surfaced again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated candidate"),
            @ApiResponse(responseCode = "404", description = "Unknown candidate id")
    })
    @PostMapping("/{candidateId}/reject")
    public ShadowIntentCandidate reject(
            @Parameter(description = "shadow_intent_candidates.id") @PathVariable UUID candidateId,
            @Parameter(description = "Optional reviewer note, max 1000 chars")
            @org.springframework.web.bind.annotation.RequestBody(required = false) ReviewDecision decision) {
        return service.reject(candidateId, decision == null ? null : decision.reviewNote());
    }
}
