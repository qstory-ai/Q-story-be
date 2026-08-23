package com.qstory.backend.storyadmin.service;

import com.qstory.backend.common.enums.RevisionOperation;
import com.qstory.backend.common.enums.RevisionTarget;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.provider.openrouter.SynthesizedAudio;
import com.qstory.backend.provider.ProviderReadiness;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.story.entity.StoryAsset;
import com.qstory.backend.story.entity.StorySegment;
import com.qstory.backend.story.repository.StoryAssetRepository;
import com.qstory.backend.story.repository.StorySegmentRepository;
import com.qstory.backend.voicecast.service.VoiceCastService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-records narration whose script has been edited.
 *
 * <p>Fixed narration ships as audio rendered from the content files, so editing a line in the
 * database changed the caption a child reads while the voice kept saying the old words. Marking
 * that mismatch (StorySegment.narrationText) only made it visible; this closes it.
 *
 * <p>Runs one segment at a time and commits each independently: a TTS call can fail halfway through
 * a story, and a partial re-render where every finished clip is correct is a far better place to
 * resume from than a rollback that discards them.
 */
@Service
public class NarrationRerenderService {

    /** The narration clip that plays for an utterance is the audio asset sharing its slug. */
    private static final String NARRATION_CATEGORY = "NARRATION";

    private final StorySegmentRepository segmentRepository;
    private final StoryAssetRepository assetRepository;
    private final OpenRouterClient openRouterClient;
    private final VoiceCastService voiceCastService;
    private final SupabaseStorageClient storageClient;
    private final StoryRevisionService revisionService;
    private final AppProperties config;

    public NarrationRerenderService(
            StorySegmentRepository segmentRepository,
            StoryAssetRepository assetRepository,
            OpenRouterClient openRouterClient,
            VoiceCastService voiceCastService,
            SupabaseStorageClient storageClient,
            StoryRevisionService revisionService,
            AppProperties config) {
        this.segmentRepository = segmentRepository;
        this.assetRepository = assetRepository;
        this.openRouterClient = openRouterClient;
        this.voiceCastService = voiceCastService;
        this.storageClient = storageClient;
        this.revisionService = revisionService;
        this.config = config;
    }

    public record StaleLine(String segmentId, String sceneId, String spoken, String written) {}

    /** What a re-render would cover: every utterance whose script no longer matches its recording. */
    @Transactional(readOnly = true)
    public List<StaleLine> staleLines(String storyId) {
        List<StaleLine> stale = new ArrayList<>();
        for (StorySegment segment : segmentRepository
                .findByScene_Story_IdOrderByScene_SequenceAscDisplayOrderAsc(storyId)) {
            String written = text(segment);
            if (segment.getNarrationText() != null && !Objects.equals(segment.getNarrationText(), written)) {
                stale.add(new StaleLine(
                        segment.getId().toString(), segment.getScene().getId(),
                        segment.getNarrationText(), written));
            }
        }
        return stale;
    }

    /**
     * Re-records one line. Not batched over a whole story on purpose: each call is a paid provider
     * request against a live story, so the caller decides how many to spend, and a failure names
     * exactly one line rather than leaving a run half-applied with nothing saying where it stopped.
     */
    @Transactional
    public Map<String, Object> rerender(String storyId, UUID segmentId, UUID authorId) {
        // Checked before the work starts rather than left to surface as a provider failure two
        // calls later, where it reads as "the recording broke" instead of "TTS was never set up".
        if (!ProviderReadiness.of(config).tts()) {
            throw ApiException.contractError(
                    ErrorCode.INTERNAL_ERROR, "음성 합성이 아직 설정되지 않았어요.", 500);
        }
        if (!config.supabase().configured()) {
            throw ApiException.contractError(
                    ErrorCode.INTERNAL_ERROR, "녹음을 저장할 준비가 아직 끝나지 않았어요.", 500);
        }
        StorySegment segment = segmentRepository.findById(segmentId)
                .filter(candidate -> candidate.getScene().getStory().getId().equals(storyId))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "그 문장을 찾지 못했어요.", 404));
        if (!"utterance".equals(segment.getKind())) {
            throw ApiException.contractError(
                    ErrorCode.VALIDATION_FAILED, "낭독 문장만 다시 녹음할 수 있어요.", 400);
        }

        String written = text(segment);
        String speaker = String.valueOf(segment.getPayload().get("speaker"));
        var cast = voiceCastService.voiceCastForSpeaker(storyId, speaker);
        String ttsInput = voiceCastService.buildGeminiTtsPerformanceInput(storyId, speaker, written);

        SynthesizedAudio audio = openRouterClient.synthesize(
                ttsInput, cast.voice(), 1.0, RequestDeadline.startingNow(config.requestTimeoutMs()));

        // Rendered clips are addressed by segment id, not by the slug of the file they replace: the
        // original ships with the frontend build and stays where it is, so a re-render that is later
        // reverted can fall back to it instead of having overwritten it.
        String objectName = "%s/%s.mp3".formatted(storyId, segmentId);
        String bucket = config.supabase().storyAudioBucket();
        if (!storageClient.upload(bucket, objectName, audio.audio(), "audio/mpeg")) {
            throw ApiException.contractError(
                    ErrorCode.INTERNAL_ERROR, "녹음을 저장하지 못했어요.", 500);
        }
        String url = "%s/storage/v1/object/public/%s/%s".formatted(
                config.supabase().url().replaceAll("/+$", ""), bucket, objectName);

        StoryAsset asset = narrationAssetFor(storyId, segment);
        Map<String, Object> before = describe(asset, segment);
        asset.setFile(url);
        asset.setRenderedAt(Instant.now());
        asset.setRenderedVoice(cast.voice());
        assetRepository.save(asset);
        // The recording now says the written line, which is what clears the mismatch.
        segment.setNarrationText(written);
        segmentRepository.save(segment);

        Map<String, Object> after = describe(asset, segment);
        revisionService.record(
                storyId, RevisionTarget.ASSET, asset.getSlug(), RevisionOperation.UPDATE,
                before, after, authorId, "narration re-rendered");
        return after;
    }

    /**
     * The clip slug is derived from the segment's position the same way the content pipeline derives
     * it, which is the only link between a line and its recording - segments carry no asset id.
     */
    private StoryAsset narrationAssetFor(String storyId, StorySegment segment) {
        String sceneLocalId = segment.getScene().getId().startsWith(storyId + "-")
                ? segment.getScene().getId().substring(storyId.length() + 1)
                : segment.getScene().getId();
        String slug = "%s-%03d".formatted(
                sceneLocalId.toLowerCase().replace('_', '-'), segment.getDisplayOrder() + 1);
        return assetRepository.findByStory_IdOrderBySlugAsc(storyId).stream()
                .filter(candidate -> candidate.getSlug().equals(slug)
                        && NARRATION_CATEGORY.equals(candidate.getCategory().name()))
                .findFirst()
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.NOT_FOUND, "그 문장의 녹음을 찾지 못했어요.", 404));
    }

    private String text(StorySegment segment) {
        Object value = segment.getPayload().get("text");
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> describe(StoryAsset asset, StorySegment segment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("slug", asset.getSlug());
        view.put("file", asset.getFile());
        view.put("renderedAt", asset.getRenderedAt());
        view.put("renderedVoice", asset.getRenderedVoice());
        view.put("narrationText", segment.getNarrationText());
        return view;
    }
}
