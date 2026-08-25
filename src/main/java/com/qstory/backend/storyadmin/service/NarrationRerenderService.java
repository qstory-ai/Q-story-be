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
 * 대본이 수정된 내레이션을 다시 녹음한다.
 *
 * <p>고정 내레이션은 콘텐츠 파일로부터 렌더링된 오디오로 배포되므로, 데이터베이스에서 한 줄을
 * 수정하면 아이가 읽는 자막은 바뀌지만 음성은 여전히 예전 단어를 말하고 있게 된다. 이 불일치를
 * 표시(StorySegment.narrationText)하는 것만으로는 눈에 보이게 만들었을 뿐이고, 이 서비스가 그것을
 * 실제로 해소한다.
 *
 * <p>한 번에 세그먼트 하나씩 실행하고 각각을 독립적으로 커밋한다: TTS 호출은 스토리를 처리하는
 * 도중에도 실패할 수 있는데, 완료된 클립은 모두 정상인 부분 재녹음 상태가, 그것들을 전부 버리는
 * 롤백보다 재개하기에 훨씬 나은 지점이기 때문이다.
 */
@Service
public class NarrationRerenderService {

    /** 어떤 대사(utterance)에서 재생되는 내레이션 클립은, 그 대사와 slug를 공유하는 오디오 asset이다. */
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

    /** 재녹음이 커버하는 대상: 대본이 더 이상 녹음과 일치하지 않는 모든 대사. */
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
     * 대사 한 줄을 다시 녹음한다. 스토리 전체를 대상으로 배치 처리하지 않는 것은 의도적이다: 호출
     * 한 번마다 운영 중인 스토리에 대해 비용이 발생하는 프로바이더 요청이 나가므로, 몇 번을 쓸지는
     * 호출자가 결정하게 하고, 실패했을 때도 정확히 어느 한 줄에서 실패했는지 알 수 있게 하기 위함이다
     * - 어디서 멈췄는지 알 수 없는 채로 절반만 적용된 상태로 남기지 않는다.
     */
    @Transactional
    public Map<String, Object> rerender(String storyId, UUID segmentId, UUID authorId) {
        // 작업이 시작되기 전에 미리 확인한다. 그렇지 않으면 몇 단계 뒤에 프로바이더 오류로
        // 나타나게 되는데, 그러면 "TTS가 아예 설정된 적이 없다"가 아니라 "녹음이 고장났다"처럼
        // 읽히게 된다.
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

        // 렌더링된 클립은 자신이 대체하는 파일의 slug가 아니라 segment id로 주소가 지정된다:
        // 원본은 프론트엔드 빌드와 함께 배포되어 그 자리에 그대로 남아 있으므로, 이후 재녹음이
        // 되돌려지더라도 원본을 덮어쓰지 않은 덕분에 그 원본으로 폴백할 수 있다.
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
        // 이제 녹음이 대본에 적힌 대사와 일치하게 되었으므로, 이것으로 불일치가 해소된다.
        segment.setNarrationText(written);
        segmentRepository.save(segment);

        Map<String, Object> after = describe(asset, segment);
        revisionService.record(
                storyId, RevisionTarget.ASSET, asset.getSlug(), RevisionOperation.UPDATE,
                before, after, authorId, "narration re-rendered");
        return after;
    }

    /**
     * 클립의 slug는, 콘텐츠 파이프라인이 이를 도출하는 방식과 동일하게, segment의 위치로부터
     * 도출된다. 이것이 대사 한 줄과 그 녹음을 연결하는 유일한 고리다 - segment는 asset id를
     * 별도로 가지고 있지 않다.
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
