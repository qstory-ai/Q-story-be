package com.qstory.backend.shadow.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.shadow.entity.ShadowFamilyDraft;
import com.qstory.backend.shadow.entity.ShadowIntentCandidate;
import com.qstory.backend.shadow.service.ShadowFamilyGenerationService;
import com.qstory.backend.shadow.service.ShadowReviewService;
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
 * shadow_intent_candidates에 대한 사람이 검토하는 화면 - 이 콘텐츠는 아이에게 직접 노출되는 일이 절대 없으며
 * (ShadowIntentCollectionService 참고), 검토자가 여기서 승인한 후보만 generate-draft로 family 초안을
 * 만들 수 있다(ShadowFamilyGenerationService).
 */
@Tag(name = "Shadow review", description = "Internal reviewer surface for shadow_intent_candidates - normalized, repeated child intents the current content doesn't cover, collected from telemetry")
@RestController
@RequestMapping("/v1/shadow/candidates")
public class ShadowReviewController {

    private final ShadowReviewService service;
    private final ShadowFamilyGenerationService generationService;
    private final CurrentUserResolver currentUserResolver;

    public ShadowReviewController(
            ShadowReviewService service, ShadowFamilyGenerationService generationService,
            CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.generationService = generationService;
        this.currentUserResolver = currentUserResolver;
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
            description = "Marks the candidate APPROVED. Never triggers content generation itself - call "
                    + "POST /{candidateId}/generate-draft separately.")
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

    @Operation(
            summary = "Generate a shadow family draft for an approved candidate",
            description = "Calls the LLM/image/TTS providers to draft a new interactive branch, then stores it "
                    + "auto-approved (no human review of the draft itself - only the automated LLM review gate "
                    + "in ShadowFamilyGenerationService, a deliberate product decision). Requires the candidate "
                    + "to already be APPROVED by a human reviewer. STAFF only - each call is a paid provider "
                    + "request, same gating as NarrationRerenderController.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Generated (or re-generated) draft"),
            @ApiResponse(responseCode = "400", description = "Candidate is not APPROVED yet"),
            @ApiResponse(responseCode = "404", description = "Unknown candidate id")
    })
    @PostMapping("/{candidateId}/generate-draft")
    public ShadowFamilyDraft generateDraft(
            @Parameter(description = "shadow_intent_candidates.id") @PathVariable UUID candidateId) {
        currentUserResolver.requireRole(Role.STAFF);
        return generationService.generateDraft(candidateId);
    }
}
