package com.qstory.backend.shadow.controller;

import com.qstory.backend.common.enums.ReviewStatus;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.shadow.entity.ShadowFamilyDraft;
import com.qstory.backend.shadow.repository.ShadowFamilyDraftRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아동이 실제로 쓰는(child-facing) 유일한 shadow 엔드포인트 - fe/q-story-beta-player-main의
 * server/src/shadow-runtime.mjs가 여기만 호출한다. 인증 불필요(StoryController.content()/
 * QuestionController와 같은 패턴): 베타 플레이어는 로그인 개념이 없는 익명 세션이다.
 */
@Tag(name = "Shadow families", description = "Public read of the latest approved shadow family per anchor, for the beta player")
@RestController
public class ShadowFamilyController {

    private static final int SIGNED_URL_TTL_SECONDS = 300;

    private final ShadowFamilyDraftRepository draftRepository;
    private final SupabaseStorageClient storageClient;
    private final AppProperties config;

    public ShadowFamilyController(
            ShadowFamilyDraftRepository draftRepository, SupabaseStorageClient storageClient, AppProperties config) {
        this.draftRepository = draftRepository;
        this.storageClient = storageClient;
        this.config = config;
    }

    public record ShadowFamilyResponse(
            String candidateId,
            String proposedFamilyId,
            String meaning,
            String acknowledgementText,
            String entryState,
            String exitState,
            List<Map<String, Object>> beats,
            String rejoinAnchorId,
            String imageUrl,
            String audioUrl,
            String imageMimeType) {}

    @Operation(
            summary = "Get the latest approved shadow family for a question anchor",
            description = "No authentication required. Returns 204 if no APPROVED draft exists for this anchor. "
                    + "imageUrl/audioUrl are freshly signed (qstoryAssetsBucket is private) and expire in "
                    + SIGNED_URL_TTL_SECONDS + " seconds - callers must fetch and use them promptly.")
    @ApiResponse(responseCode = "200", description = "Latest approved draft")
    @ApiResponse(responseCode = "204", description = "No approved draft for this anchor")
    @GetMapping("/v1/shadow/families/{anchorId}")
    public ResponseEntity<ShadowFamilyResponse> latestApproved(
            @Parameter(description = "Question anchor id, e.g. \"HG-Q-A\"") @PathVariable String anchorId) {
        List<ShadowFamilyDraft> drafts = draftRepository
                .findByCandidate_AnchorIdAndReviewStatusOrderByGeneratedAtDesc(anchorId, ReviewStatus.APPROVED);
        if (drafts.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        ShadowFamilyDraft draft = drafts.get(0);
        String bucket = config.supabase().shadowAssetsBucket();
        String imageUrl = storageClient.createSignedUrl(bucket, draft.getImageObjectName(), SIGNED_URL_TTL_SECONDS);
        String audioUrl = storageClient.createSignedUrl(bucket, draft.getAudioObjectName(), SIGNED_URL_TTL_SECONDS);
        if (imageUrl == null || audioUrl == null) {
            // 자산에 접근할 수 없으면 이 family를 아예 후보에서 빼는 게, 이미지나 오디오가 깨진
            // 채로 아이에게 노출되는 것보다 안전하다.
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new ShadowFamilyResponse(
                draft.getCandidate().getId().toString(),
                draft.getProposedFamilyId(),
                draft.getIntentSummary(),
                draft.getAcknowledgementText(),
                draft.getEntryState(),
                draft.getExitState(),
                draft.getBeats(),
                draft.getRejoinAnchorId(),
                imageUrl,
                audioUrl,
                draft.getImageMimeType()));
    }
}
