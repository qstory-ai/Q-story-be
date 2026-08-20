package com.qstory.backend.shadow;

import com.qstory.backend.persistence.entity.ShadowIntentCandidate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A human review surface for shadow_intent_candidates - this content is never shown to a child
 * directly (see ShadowIntentCollectionService); a reviewer approves/rejects here before any of it
 * can inform authored content. Drafting the actual replacement story content (calling an LLM/image
 * model to write a candidate family) is intentionally out of scope, mirroring the original repo,
 * where shadow-generation.mjs was a template/validation library with no wired-up caller either.
 */
@RestController
@RequestMapping("/v1/shadow/candidates")
public class ShadowReviewController {

    private final ShadowReviewService service;

    public ShadowReviewController(ShadowReviewService service) {
        this.service = service;
    }

    @GetMapping("/ready-for-review")
    public List<ShadowIntentCandidate> readyForReview() {
        return service.readyForReview();
    }

    public record ReviewDecision(String reviewNote) {}

    @PostMapping("/{candidateId}/approve")
    public ShadowIntentCandidate approve(@PathVariable UUID candidateId, @RequestBody(required = false) ReviewDecision decision) {
        return service.approve(candidateId, decision == null ? null : decision.reviewNote());
    }

    @PostMapping("/{candidateId}/reject")
    public ShadowIntentCandidate reject(@PathVariable UUID candidateId, @RequestBody(required = false) ReviewDecision decision) {
        return service.reject(candidateId, decision == null ? null : decision.reviewNote());
    }
}
