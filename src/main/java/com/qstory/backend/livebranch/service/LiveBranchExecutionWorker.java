package com.qstory.backend.livebranch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.choicecopy.ChoiceCopyVariant;
import com.qstory.backend.choicecopy.service.ChoiceCopyRegistry;
import com.qstory.backend.common.enums.AssetCategory;
import com.qstory.backend.common.enums.FamilyOrigin;
import com.qstory.backend.common.enums.LiveBranchJobStatus;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.familydraft.util.FamilyDraftHarness;
import com.qstory.backend.livebranch.entity.LiveBranchJob;
import com.qstory.backend.livebranch.repository.LiveBranchJobRepository;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.story.entity.Story;
import com.qstory.backend.story.entity.StoryActionFamily;
import com.qstory.backend.story.entity.StoryAnchor;
import com.qstory.backend.story.entity.StoryAsset;
import com.qstory.backend.story.entity.StoryFallbackSegment;
import com.qstory.backend.story.entity.StoryVisualReferencePack;
import com.qstory.backend.story.repository.StoryActionFamilyRepository;
import com.qstory.backend.story.repository.StoryAnchorRepository;
import com.qstory.backend.story.repository.StoryAssetRepository;
import com.qstory.backend.story.repository.StoryFallbackSegmentRepository;
import com.qstory.backend.story.repository.StoryRepository;
import com.qstory.backend.story.repository.StoryVisualReferencePackRepository;
import com.qstory.backend.story.service.StoryContentAssemblyService;
import com.qstory.backend.story.service.StoryRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * LiveBranchGenerationService.enqueue()가 큐에 넣은 작업을 실제로 실행한다. Phase 2부터는 family
 * 하나가 아니라 최대 3개(TARGET_COUNT)를 병렬로 만든다: 기존 하네스 루프(초안 -> 검증 -> 검수게이트 ->
 * 재시도, MAX_ATTEMPTS=3)를 family당 그대로 재사용하되, {@link #generateOneFamily}를
 * {@code liveBranchExecutor} 풀에 최대 3개 동시 제출한다. 검증/검수를 통과 못한 family는 버리고
 * 나머지로 진행하며, 3개 미만이면 해당 앵커의 기존 family(사용된 적 없는 것 우선- 여기서는 단순화해
 * displayOrder가 앞선 것부터)로 남은 자리를 채워 항상 정확히 3개(가능한 한)의 옵션을 만든다. 오디오는
 * 절대 만들지 않는다: NARRATION/BRIDGE StoryAsset을 만들지 않으면 클라이언트가 실시간 TTS 폴백
 * (POST /v1/narrations)을 그대로 태운다.
 *
 * <p>{@code @Async} 메서드는 반드시 다른 빈(LiveBranchGenerationService)을 통해 호출되어야
 * 프록시가 적용된다 - 이 클래스 안에서 스스로를 호출하면 안 된다. run() 자신은 liveBranchExecutor
 * 풀에서 실행되고, join()으로 기다리는 하위 3개 서브 작업은 별도의 liveBranchSubtaskExecutor 풀에
 * 제출한다 - 두 역할이 같은 풀을 공유하면 동시 진행 job 수가 그 풀의 corePoolSize에 도달하는 순간
 * 데드락이 난다(AsyncConfig의 클래스 주석 참고).
 */
@Component
public class LiveBranchExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(LiveBranchExecutionWorker.class);
    private static final int MAX_ATTEMPTS = 3;

    /** 한 job이 만들어야 하는 최종 옵션 개수 - 새로 생성된 family + 기존 family로 채운 자리 = 이 값. */
    private static final int TARGET_COUNT = 3;

    private final LiveBranchJobRepository jobRepository;
    private final StoryRepository storyRepository;
    private final StoryAnchorRepository anchorRepository;
    private final StoryActionFamilyRepository familyRepository;
    private final StoryFallbackSegmentRepository fallbackSegmentRepository;
    private final StoryAssetRepository assetRepository;
    private final StoryVisualReferencePackRepository visualReferencePackRepository;
    private final OpenRouterClient openRouterClient;
    private final SupabaseStorageClient storageClient;
    private final ObjectMapper objectMapper;
    private final AppProperties config;
    private final StoryRegistry storyRegistry;
    private final ChoiceCopyRegistry choiceCopyRegistry;
    private final StoryContentAssemblyService assemblyService;
    private final Executor liveBranchSubtaskExecutor;
    private final FamilyDraftHarness harness;

    public LiveBranchExecutionWorker(
            LiveBranchJobRepository jobRepository, StoryRepository storyRepository,
            StoryAnchorRepository anchorRepository, StoryActionFamilyRepository familyRepository,
            StoryFallbackSegmentRepository fallbackSegmentRepository, StoryAssetRepository assetRepository,
            StoryVisualReferencePackRepository visualReferencePackRepository, OpenRouterClient openRouterClient,
            SupabaseStorageClient storageClient, ObjectMapper objectMapper, AppProperties config,
            StoryRegistry storyRegistry, ChoiceCopyRegistry choiceCopyRegistry,
            StoryContentAssemblyService assemblyService,
            @Qualifier("liveBranchSubtaskExecutor") Executor liveBranchSubtaskExecutor,
            FamilyDraftHarness harness) {
        this.jobRepository = jobRepository;
        this.storyRepository = storyRepository;
        this.anchorRepository = anchorRepository;
        this.familyRepository = familyRepository;
        this.fallbackSegmentRepository = fallbackSegmentRepository;
        this.assetRepository = assetRepository;
        this.visualReferencePackRepository = visualReferencePackRepository;
        this.openRouterClient = openRouterClient;
        this.storageClient = storageClient;
        this.objectMapper = objectMapper;
        this.config = config;
        this.storyRegistry = storyRegistry;
        this.choiceCopyRegistry = choiceCopyRegistry;
        this.assemblyService = assemblyService;
        this.liveBranchSubtaskExecutor = liveBranchSubtaskExecutor;
        this.harness = harness;
    }

    /** generateOneFamily() 성공 결과 - draft 자체는 commitAll()이 그대로 다시 읽어 쓴다. */
    private record GeneratedFamily(
            String familyId, JsonNode draft, String imageUrl, String imageIntegrity, String bucket, String objectName) {}

    @Async("liveBranchExecutor")
    public void run(UUID jobId) {
        LiveBranchJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("live-branch.job-missing jobId={}", jobId);
            return;
        }
        try {
            job.setStatus(LiveBranchJobStatus.GENERATING);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);

            StoryAnchor anchor = anchorRepository.findById(job.getAnchorId()).orElse(null);
            if (anchor == null) {
                failJob(job, "ANCHOR_MISSING");
                return;
            }
            List<StoryActionFamily> existingFamilies =
                    familyRepository.findByAnchor_IdOrderByDisplayOrderAsc(anchor.getId());
            Set<String> rejoinCandidates = new LinkedHashSet<>(
                    familyRepository.findDistinctRejoinTargetsByAnchorId(anchor.getId()));
            rejoinCandidates.add(anchor.getDefaultRejoinAt());

            byte[] referenceBytes = readReferenceImage(anchor.getSlot());
            // 3개 서브 작업이 각자 조회하면 같은 앵커의 같은 라벨 풀을 최대 3번 DB에 왕복하게 되므로,
            // 앵커가 허용하는 화자 전체에 대해 한 번만 조회해 맵으로 넘긴다.
            Map<String, List<String>> visualFactsByLabel = visualFactsByLabel(job.getStoryId(), anchor);

            List<CompletableFuture<GeneratedFamily>> futures = new ArrayList<>();
            for (int subIndex = 0; subIndex < TARGET_COUNT; subIndex++) {
                int index = subIndex;
                futures.add(CompletableFuture.supplyAsync(
                        () -> generateOneFamily(
                                index, anchor, existingFamilies, rejoinCandidates, job, referenceBytes, visualFactsByLabel),
                        liveBranchSubtaskExecutor));
            }
            List<GeneratedFamily> generated = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();

            int paddingNeeded = TARGET_COUNT - generated.size();
            List<StoryActionFamily> padding = paddingNeeded > 0
                    ? existingFamilies.stream().limit(paddingNeeded).toList()
                    : List.of();

            if (generated.isEmpty() && padding.isEmpty()) {
                failJob(job, "REVIEW_EXHAUSTED");
                return;
            }

            int nextDisplayOrder = existingFamilies.stream()
                    .mapToInt(StoryActionFamily::getDisplayOrder).max().orElse(-1) + 1;
            try {
                commitAll(jobId, job.getStoryId(), anchor.getId(), nextDisplayOrder, generated, padding);
            } catch (DataIntegrityViolationException conflict) {
                for (GeneratedFamily family : generated) {
                    storageClient.delete(family.bucket(), family.objectName());
                }
                failJob(jobRepository.findById(jobId).orElse(job), "FAMILY_ID_CONFLICT");
            }
        } catch (Exception unexpected) {
            log.warn("live-branch.generation-failed jobId={}", jobId, unexpected);
            failJob(jobRepository.findById(jobId).orElse(job), "GENERATION_FAILED");
        }
    }

    /**
     * family 하나의 하네스 루프(초안 재시도 -> 이미지 생성 -> 업로드) 전체를 담당하는 서브 작업 -
     * 실패하면(재시도 소진, 이미지 생성/업로드 실패 포함) null을 반환할 뿐 예외를 던지지 않는다:
     * 다른 두 서브 작업이 계속 진행되어야 하기 때문이다(하나가 실패해도 job 전체를 실패시키지 않음).
     */
    private GeneratedFamily generateOneFamily(
            int subIndex, StoryAnchor anchor, List<StoryActionFamily> existingFamilies, Set<String> rejoinCandidates,
            LiveBranchJob job, byte[] referenceBytes, Map<String, List<String>> visualFactsByLabel) {
        try {
            JsonNode draft = null;
            List<String> revisionFeedback = List.of();
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                JsonNode attemptDraft =
                        requestDraft(anchor, existingFamilies, rejoinCandidates, job, revisionFeedback, freshDeadline());
                String issue = validationIssue(attemptDraft, anchor, rejoinCandidates);
                if (issue != null) {
                    revisionFeedback = List.of(issue);
                    continue;
                }
                List<String> reviewIssues = reviewGateIssues(anchor, attemptDraft, freshDeadline());
                if (!reviewIssues.isEmpty()) {
                    revisionFeedback = reviewIssues;
                    continue;
                }
                draft = attemptDraft;
                break;
            }
            if (draft == null) {
                log.warn("live-branch.sub-generation-exhausted jobId={} subIndex={}", job.getId(), subIndex);
                return null;
            }

            String familyId = "LIVE_" + anchor.getSlot() + "_"
                    + job.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT) + "_" + subIndex;
            // 이미지 생성은 draft/review 게이트와 달리 실패 시 지금까지는 곧바로 null 반환 - 그 자리는
            // 상위(run)에서 기존 family로 padding됐다. 이미지 자체는 transient한 이유(OpenRouter 스로틀,
            // 일시 네트워크 에러)로 실패하는 경우가 많아 1회 재시도만 넣어도 신선한 옵션 유지 확률이
            // 눈에 띄게 오른다. 여전히 실패하면 기존 padding 폴백이 안전망이다.
            OpenRouterClient.GeneratedImage image = generateImageWithOneRetry(
                    buildImagePrompt(visualFactsByLabel, anchor, draft), referenceBytes, job.getId(), subIndex);
            if (image == null) {
                return null;
            }

            String imageExtension = switch (image.mimeType()) {
                case "image/png" -> "png";
                case "image/jpeg" -> "jpg";
                default -> "webp";
            };
            String objectName = job.getId() + "/branch-art-" + subIndex + "." + imageExtension;
            String bucket = config.supabase().storyImageBucket();
            if (!storageClient.upload(bucket, objectName, image.bytes(), image.mimeType())) {
                storageClient.delete(bucket, objectName);
                log.warn("live-branch.sub-image-upload-failed jobId={} subIndex={}", job.getId(), subIndex);
                return null;
            }
            String imageUrl = "%s/storage/v1/object/public/%s/%s".formatted(
                    config.supabase().url().replaceAll("/+$", ""), bucket, objectName);
            return new GeneratedFamily(familyId, draft, imageUrl, sha256Integrity(image.bytes()), bucket, objectName);
        } catch (Exception error) {
            log.warn("live-branch.sub-generation-failed jobId={} subIndex={}", job.getId(), subIndex, error);
            return null;
        }
    }

    /**
     * 실제 콘텐츠를 쓰는 유일한 지점. 새로 생성된 family마다 StoryActionFamily(origin=LIVE_GENERATED,
     * requiresFamilyId/rejoinSlot/rejoinTarget을 즉시 채운다) + StoryFallbackSegment 목록 +
     * StoryAsset(이미지) 하나를 insert하고, 부족분은 기존 family를 그대로 옵션에 포함시킨다(새로
     * insert하지 않음). NARRATION/BRIDGE 카테고리 asset은 절대 만들지 않는다. reload() 세 개는 반드시
     * 마지막 statement여야 한다 - 그 뒤에 실패할 코드가 있으면 롤백 시 "유령 family"가 잠깐 서빙될 수
     * 있다.
     */
    @Transactional
    public void commitAll(
            UUID jobId, String storyId, String anchorId, int startDisplayOrder,
            List<GeneratedFamily> generated, List<StoryActionFamily> padding) {
        LiveBranchJob job = jobRepository.findById(jobId).orElseThrow();
        StoryAnchor anchor = anchorRepository.findById(anchorId).orElseThrow();
        Story story = storyRepository.findById(storyId).orElseThrow();

        List<Map<String, Object>> resultOptions = new ArrayList<>();
        int displayOrder = startDisplayOrder;
        for (GeneratedFamily generatedFamily : generated) {
            JsonNode draft = generatedFamily.draft();
            String familyId = generatedFamily.familyId();
            String rejoinAnchorId = draft.path("rejoinAnchorId").asText();
            String assetSlug = familyId.toLowerCase(Locale.ROOT).replace('_', '-') + "-01";

            StoryActionFamily family = StoryActionFamily.builder()
                    .id(familyId)
                    .anchor(anchor)
                    .meaning(draft.path("meaning").asText())
                    .acknowledgementText(draft.path("acknowledgementText").asText())
                    .reportSummary(draft.path("reportSummary").asText())
                    .bridgeAudioId(familyId.toLowerCase(Locale.ROOT).replace('_', '-') + "-bridge")
                    .branchAssetId(assetSlug)
                    .requiresPriorFamilyIds(List.of())
                    .displayOrder(displayOrder++)
                    .choiceCopyVariants(toChoiceCopyVariants(draft.path("choiceCopy")))
                    .requiresFamilyId(null)
                    .rejoinSlot(anchor.getSlot())
                    .rejoinTarget(rejoinAnchorId)
                    .origin(FamilyOrigin.LIVE_GENERATED)
                    .build();
            familyRepository.save(family);
            // 아래 fallback segment insert보다 먼저 family insert를 실제로 실행시켜, family id 충돌
            // (DataIntegrityViolationException)이 여기서 곧바로 드러나게 한다 - run()이 이를 재시도
            // 트리거로 다룬다(전체 커밋이 롤백되고 이미 업로드된 이미지는 run()이 정리한다).
            familyRepository.flush();

            List<StoryFallbackSegment> segments = buildSegments(family, draft, assetSlug, storyId, rejoinAnchorId);
            fallbackSegmentRepository.saveAll(segments);

            StoryAsset asset = StoryAsset.builder()
                    .story(story)
                    .slug(assetSlug)
                    .category(AssetCategory.BRANCH_ART)
                    .file(generatedFamily.imageUrl())
                    .integrity(generatedFamily.imageIntegrity())
                    .familyId(familyId)
                    .panel(1)
                    .build();
            assetRepository.save(asset);

            resultOptions.add(toResultOption(familyId, firstChoiceCopyLabel(draft.path("choiceCopy")), family.getMeaning()));
        }

        for (StoryActionFamily existing : padding) {
            resultOptions.add(toResultOption(existing.getId(), firstChoiceCopyLabel(existing), existing.getMeaning()));
        }

        job.setStatus(LiveBranchJobStatus.READY);
        job.setResultOptions(resultOptions);
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);

        storyRegistry.reload();
        choiceCopyRegistry.reload();
        assemblyService.reload();
    }

    private Map<String, Object> toResultOption(String familyId, String label, String meaning) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("familyId", familyId);
        option.put("label", label);
        option.put("meaning", meaning);
        return option;
    }

    private String firstChoiceCopyLabel(JsonNode choiceCopyArray) {
        if (choiceCopyArray != null && choiceCopyArray.isArray() && !choiceCopyArray.isEmpty()) {
            String label = choiceCopyArray.get(0).path("label").asText("");
            if (!label.isBlank()) {
                return label;
            }
        }
        return "새로운 방법";
    }

    private String firstChoiceCopyLabel(StoryActionFamily family) {
        List<ChoiceCopyVariant> variants = family.getChoiceCopyVariants();
        if (variants != null && !variants.isEmpty() && variants.get(0).label() != null) {
            return variants.get(0).label();
        }
        return family.getMeaning();
    }

    private List<StoryFallbackSegment> buildSegments(
            StoryActionFamily family, JsonNode draft, String assetSlug, String storyId, String rejoinAnchorId) {
        List<StoryFallbackSegment> segments = new ArrayList<>();
        int order = 0;
        String visualId = storyId + "-VIS-" + family.getId() + "-01";

        Map<String, Object> visualPayload = new LinkedHashMap<>();
        visualPayload.put("id", visualId);
        visualPayload.put("assetId", assetSlug);
        visualPayload.put("mode", slugifyMode(draft.path("imageBrief").path("action").asText("")));
        segments.add(StoryFallbackSegment.builder()
                .family(family).displayOrder(order++).kind("visual").branchPoint(false).payload(visualPayload)
                .build());

        for (JsonNode beat : draft.path("beats")) {
            Map<String, Object> narratorPayload = new LinkedHashMap<>();
            narratorPayload.put("visualId", visualId);
            narratorPayload.put("speaker", "NARRATOR");
            narratorPayload.put("role", "FALLBACK");
            narratorPayload.put("text", beat.path("narratorText").asText());
            segments.add(StoryFallbackSegment.builder()
                    .family(family).displayOrder(order++).kind("utterance").branchPoint(false)
                    .payload(narratorPayload).build());

            for (JsonNode line : beat.path("dialogue")) {
                Map<String, Object> dialoguePayload = new LinkedHashMap<>();
                dialoguePayload.put("visualId", visualId);
                // draftSchema()가 LLM 출력을 speakerId 형식으로 강제하므로(라인 626 근처의 enum 참고),
                // narratorPayload의 "NARRATOR"와 같은 짧은 캐스트 태그로 맞춰준다 - 그렇지 않으면
                // story-package.ts의 addSpeaker()가 castByTag 조회에 실패해 스토리 로드 전체가 깨진다.
                dialoguePayload.put("speaker", normalizeCharacterLabel(line.path("speaker").asText()));
                dialoguePayload.put("role", "DIALOGUE");
                dialoguePayload.put("text", line.path("text").asText());
                segments.add(StoryFallbackSegment.builder()
                        .family(family).displayOrder(order++).kind("utterance").branchPoint(false)
                        .payload(dialoguePayload).build());
            }
        }

        Map<String, Object> rejoinPayload = new LinkedHashMap<>();
        rejoinPayload.put("slot", family.getAnchor().getSlot());
        rejoinPayload.put("target", rejoinAnchorId);
        segments.add(StoryFallbackSegment.builder()
                .family(family).displayOrder(order).kind("rejoin").branchPoint(false).payload(rejoinPayload)
                .build());
        return segments;
    }

    private void failJob(LiveBranchJob job, String errorCode) {
        if (job == null) {
            return;
        }
        job.setStatus(LiveBranchJobStatus.FAILED);
        job.setErrorCode(errorCode);
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
    }

    /** 이미지 생성이 특히 느릴 수 있어 실시간 라우팅용 기본값보다 여유 있게 잡는다(ShadowFamilyGenerationService와 동일). */
    private RequestDeadline freshDeadline() {
        return harness.freshDeadline(config.requestTimeoutMs());
    }

    /**
     * 이미지 생성 1회 재시도. draft/review처럼 여러 번 왕복하지 않는 이유는 이미지 한 장 생성에만
     * 이미 수 초가 걸려서 여러 번 재시도하면 폴링 타임아웃을 넘길 위험이 있기 때문이다 - 한 번의
     * transient 실패(스로틀, 일시 네트워크 오류)만 흡수하고, 그래도 실패하면 상위에서 padding으로
     * 안전하게 폴백한다.
     */
    private OpenRouterClient.GeneratedImage generateImageWithOneRetry(
            String prompt, byte[] referenceBytes, UUID jobId, int subIndex) {
        try {
            return openRouterClient.generateImage(prompt, referenceBytes, "image/jpeg", freshDeadline());
        } catch (Exception firstError) {
            log.info("live-branch.sub-image-retry jobId={} subIndex={} cause={}",
                    jobId, subIndex, firstError.toString());
            try {
                return openRouterClient.generateImage(prompt, referenceBytes, "image/jpeg", freshDeadline());
            } catch (Exception secondError) {
                log.warn("live-branch.sub-image-retry-exhausted jobId={} subIndex={}", jobId, subIndex, secondError);
                return null;
            }
        }
    }

    /**
     * 스타일/인물 연속성을 위한 참조 이미지. 실제 StoryAsset 파일은 프런트엔드 정적 사이트에 상대
     * 경로로 배포되어 이 백엔드가 직접 HTTP로 가져올 방법이 없으므로(base URL 설정이 아예 없음),
     * ShadowFamilyGenerationService가 이미 이 세 앵커(A/B/C)에 대해 검증해 둔 클래스패스 참조
     * 이미지를 그대로 재사용한다 - 앵커가 늘어나면 이 리소스도 함께 추가해야 한다.
     */
    private byte[] readReferenceImage(String slot) {
        try (InputStream stream = new ClassPathResource("shadow-reference/HG-Q-" + slot + ".jpg").getInputStream()) {
            return stream.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("no bundled reference image for slot " + slot, error);
        }
    }

    private String sha256Integrity(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256-" + Base64.getEncoder().encodeToString(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String slugifyMode(String text) {
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isBlank()) {
            return "live-branch-scene";
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    private List<ChoiceCopyVariant> toChoiceCopyVariants(JsonNode arrayNode) {
        return objectMapper.convertValue(arrayNode, new TypeReference<List<ChoiceCopyVariant>>() {});
    }

    private JsonNode requestDraft(
            StoryAnchor anchor, List<StoryActionFamily> existingFamilies, Set<String> rejoinCandidates,
            LiveBranchJob job, List<String> revisionFeedback, RequestDeadline deadline) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jobId", job.getId().toString());
        payload.put("anchorSlot", anchor.getSlot());
        payload.put("currentScene", anchor.getSummary());
        payload.put("childTranscript", job.getChildTranscriptRedacted());
        payload.put("questionRound", job.getQuestionRound());
        ArrayNode allowedCharacters = payload.putArray("allowedCharacters");
        anchor.getAllowedSpeakerIds().forEach(allowedCharacters::add);
        ArrayNode existing = payload.putArray("existingFamilies");
        for (StoryActionFamily family : existingFamilies) {
            ObjectNode node = existing.addObject();
            node.put("id", family.getId());
            node.put("meaning", family.getMeaning());
        }
        ArrayNode rejoinIds = payload.putArray("allowedRejoinAnchorIds");
        rejoinCandidates.forEach(rejoinIds::add);
        if (!revisionFeedback.isEmpty()) {
            ArrayNode feedback = payload.putArray("revisionFeedback");
            revisionFeedback.forEach(feedback::add);
            payload.put("revisionInstruction", "앞선 초안의 탈락 이유를 수정한 완전히 새로운 초안을 쓰라.");
        }

        return openRouterClient.generateStructuredCompletion(
                draftSystemPrompt(), payload.toString(),
                draftSchema(anchor.getAllowedSpeakerIds(), rejoinCandidates), "qstory_live_branch_draft_v1", 2_400,
                ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "새 분기를 만들지 못했어요.", deadline);
    }

    private List<String> reviewGateIssues(StoryAnchor anchor, JsonNode draft, RequestDeadline deadline) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("anchorSlot", anchor.getSlot());
        return harness.reviewGateIssues(
                payload, draft, reviewSystemPrompt(), "qstory_live_branch_review_v1",
                "새 분기 검수를 완료하지 못했어요.", deadline);
    }

    /**
     * 통과하면 null, 아니면 재시도 사유 문자열 - ShadowFamilyGenerationService.validationIssue()와 같은
     * 역할(그쪽엔 없는 proposedFamilyId/location/B슬롯 검사가 없을 뿐, 공통 검사는 FamilyDraftHarness에
     * 위임한다). 검사 순서는 기존 그대로 유지한다(Shadow와 순서가 다르지만 그것도 기존 동작이다).
     *
     * <p>imageBrief.characters/dialogue.speaker는 이 스토리의 StoryCast speakerId 값(예:
     * "HG-SPK-GRETEL", StoryCast.speakerId 문서 참고)이고, anchor.getAllowedSpeakerIds()도 같은
     * speakerId 형식이다(draftSchema()의 enum이 LLM 출력을 이 형식으로 강제한다) - Shadow의
     * bare-label(contract.allowedCharacters())과는 다른 표현이지만 각자 자기 draft의 실제 형식과
     * 맞다.
     */
    private String validationIssue(JsonNode draft, StoryAnchor anchor, Set<String> rejoinCandidates) {
        String rejoinIssue = harness.rejoinAnchorIssue(draft, rejoinCandidates);
        if (rejoinIssue != null) {
            return rejoinIssue;
        }
        String choiceCopyIssue = harness.choiceCopyIssue(draft);
        if (choiceCopyIssue != null) {
            return choiceCopyIssue;
        }
        String beatsIssue = harness.beatsIssue(draft, anchor.getAllowedSpeakerIds());
        if (beatsIssue != null) {
            return beatsIssue;
        }
        String charactersIssue = harness.imageCharactersIssue(draft, anchor.getAllowedSpeakerIds());
        if (charactersIssue != null) {
            return charactersIssue;
        }
        return harness.reportSummaryIssue(draft);
    }

    /**
     * 계획 문서 Phase 2 §4: 참조 이미지 한 장에만 의존하지 않고, 캐릭터 팩의 immutableFacts를 텍스트
     * 규칙으로도 프롬프트에 덧붙여 이중 보강한다. imageBrief.characters는 이 스토리의 StoryCast
     * speakerId 값(예: "HG-SPK-GRETEL")이므로, "&lt;STORY&gt;-SPK-" 접두어를 뗀 값을 팩의 label과
     * 매칭한다(StoryVisualReferencePack.label 문서 참고). 로케이션 팩은 StoryAnchor에 아직 location
     * 필드가 없어 연결하지 않는다(열린 질문 - 최종 보고 참고).
     */
    private String buildImagePrompt(Map<String, List<String>> visualFactsByLabel, StoryAnchor anchor, JsonNode draft) {
        JsonNode imageBrief = draft.path("imageBrief");
        List<String> characters = harness.toStringList(imageBrief.path("characters"));
        String sceneLine = "Scene: " + anchor.getSummary() + ".";
        List<String> lines = harness.baseImagePromptLines(characters, sceneLine, imageBrief);
        String characterIdentityRules = characterIdentityRulesLine(visualFactsByLabel, characters);
        if (characterIdentityRules != null) {
            lines.add(characterIdentityRules);
        }
        return String.join("\n", lines);
    }

    private String characterIdentityRulesLine(Map<String, List<String>> visualFactsByLabel, List<String> speakerIds) {
        List<String> facts = new ArrayList<>();
        for (String speakerOrLabel : speakerIds.stream().map(this::normalizeCharacterLabel).distinct().toList()) {
            facts.addAll(visualFactsByLabel.getOrDefault(speakerOrLabel, List.of()));
        }
        if (facts.isEmpty()) {
            return null;
        }
        return "Character identity rules: " + String.join("; ", facts) + ".";
    }

    /** 앵커가 허용하는 화자 전체에 대해 캐릭터 비주얼 팩을 한 번만 조회한다 - 서브 작업마다 다시 조회하지 않도록. */
    private Map<String, List<String>> visualFactsByLabel(String storyId, StoryAnchor anchor) {
        List<String> labels = anchor.getAllowedSpeakerIds().stream()
                .map(this::normalizeCharacterLabel).distinct().toList();
        if (labels.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> byLabel = new LinkedHashMap<>();
        for (StoryVisualReferencePack pack : visualReferencePackRepository.findByStory_IdAndLabelIn(storyId, labels)) {
            byLabel.put(pack.getLabel(), pack.getImmutableFacts() == null ? List.of() : pack.getImmutableFacts());
        }
        return byLabel;
    }

    /** "HG-SPK-OLD_WOMAN" -> "OLD_WOMAN". 접두어가 없으면(이미 단순 라벨이면) 그대로 둔다. */
    private String normalizeCharacterLabel(String speakerOrLabel) {
        int index = speakerOrLabel.indexOf("-SPK-");
        return index >= 0 ? speakerOrLabel.substring(index + 5) : speakerOrLabel;
    }

    private ObjectNode draftSchema(List<String> allowedCharacters, Set<String> rejoinCandidates) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        harness.stringProp(properties, "meaning", 5, 200);
        harness.stringProp(properties, "acknowledgementText", 5, 200);
        harness.stringProp(properties, "reportSummary", 10, 260);

        ObjectNode choiceCopy = properties.putObject("choiceCopy");
        choiceCopy.put("type", "array");
        choiceCopy.put("minItems", 3);
        choiceCopy.put("maxItems", 3);
        ObjectNode choiceCopyItem = choiceCopy.putObject("items");
        choiceCopyItem.put("type", "object");
        choiceCopyItem.put("additionalProperties", false);
        ObjectNode choiceCopyProps = choiceCopyItem.putObject("properties");
        harness.stringProp(choiceCopyProps, "label", 3, 30);
        harness.stringProp(choiceCopyProps, "meaning", 8, 100);
        choiceCopyItem.putArray("required").add("label").add("meaning");

        ObjectNode rejoinAnchorId = properties.putObject("rejoinAnchorId");
        rejoinAnchorId.put("type", "string");
        ArrayNode rejoinEnum = rejoinAnchorId.putArray("enum");
        rejoinCandidates.forEach(rejoinEnum::add);

        ObjectNode beats = properties.putObject("beats");
        beats.put("type", "array");
        beats.put("minItems", 1);
        beats.put("maxItems", 2);
        ObjectNode beatItem = beats.putObject("items");
        beatItem.put("type", "object");
        beatItem.put("additionalProperties", false);
        ObjectNode beatProps = beatItem.putObject("properties");
        harness.stringProp(beatProps, "action", 5, 160);
        harness.stringProp(beatProps, "narratorText", 10, 320);
        ObjectNode dialogue = beatProps.putObject("dialogue");
        dialogue.put("type", "array");
        dialogue.put("minItems", 0);
        dialogue.put("maxItems", 3);
        ObjectNode dialogueItem = dialogue.putObject("items");
        dialogueItem.put("type", "object");
        dialogueItem.put("additionalProperties", false);
        ObjectNode dialogueProps = dialogueItem.putObject("properties");
        ObjectNode speaker = dialogueProps.putObject("speaker");
        speaker.put("type", "string");
        ArrayNode speakerEnum = speaker.putArray("enum");
        allowedCharacters.forEach(speakerEnum::add);
        speakerEnum.add("NARRATOR");
        harness.stringProp(dialogueProps, "text", 2, 160);
        dialogueItem.putArray("required").add("speaker").add("text");
        beatItem.putArray("required").add("action").add("narratorText").add("dialogue");

        ObjectNode imageBrief = properties.putObject("imageBrief");
        imageBrief.put("type", "object");
        imageBrief.put("additionalProperties", false);
        ObjectNode imageBriefProps = imageBrief.putObject("properties");
        ObjectNode characters = imageBriefProps.putObject("characters");
        characters.put("type", "array");
        characters.put("minItems", 1);
        characters.put("maxItems", 3);
        ObjectNode characterItems = characters.putObject("items");
        characterItems.put("type", "string");
        ArrayNode characterEnum = characterItems.putArray("enum");
        allowedCharacters.forEach(characterEnum::add);
        harness.stringProp(imageBriefProps, "action", 5, 200);
        harness.stringProp(imageBriefProps, "composition", 5, 220);
        ObjectNode continuityFacts = imageBriefProps.putObject("continuityFacts");
        continuityFacts.put("type", "array");
        continuityFacts.put("minItems", 2);
        continuityFacts.put("maxItems", 6);
        continuityFacts.putObject("items").put("type", "string");
        harness.stringProp(imageBriefProps, "negativePrompt", 10, 320);
        imageBrief.putArray("required")
                .add("characters").add("action").add("composition").add("continuityFacts").add("negativePrompt");

        schema.putArray("required")
                .add("meaning").add("acknowledgementText").add("reportSummary").add("choiceCopy")
                .add("rejoinAnchorId").add("beats").add("imageBrief");
        return schema;
    }

    private String draftSystemPrompt() {
        return String.join("\n",
                "너는 6~9세용 동화의 실시간 신규 분기 작가다. 이 결과는 사람 검수 없이 자동 게이트만",
                "통과하면 곧바로 아이에게 노출되니 더욱 신중하게 작성하라.",
                "아이의 질문 의미를 현재 장면(currentScene)에서 직접 느낄 수 있는 1~2개 행동 beat로 바꿔라.",
                "childTranscript의 핵심 감각·목적·행동을 다른 단서로 바꾸지 말고 결과 beat에 직접 보여주라.",
                "existingFamilies에 이미 있는 의미와 사실상 같은(동의어·소품만 다른) 복제본은 만들지 마라.",
                "choiceCopy 3개는 서로 다른 행동이 아니라 하나의 고정 beat를 정확히 다른 말로 표현한 문구 3세트여야 한다.",
                "currentScene에 없는 물건·능력·약속·발자국·숨은 문·마법 소품 같은 새 증거를 즉석에서 추가하지 마라.",
                "달콤한 냄새·조용한 소리·친절한 말만으로 상대나 장소가 안전하다고 결론내지 마라 - 단서를 확인해도 인물은 계속 주의를 유지해야 한다.",
                "아동이 따라 할 수 있는 구체적 폭력·상해·위험 도구 사용을 쓰지 마라.",
                "인물·장소·합류점(rejoinAnchorId)은 입력으로 주어진 허용 목록만 사용하라.",
                "삽화에는 한 beat의 핵심 행동만 보이게 하고 글자·UI·말풍선은 넣지 마라.",
                "reportSummary는 생성 과정이 아니라 아이가 고른 행동과 그 결과를 부모에게 요약하라.");
    }

    private String reviewSystemPrompt() {
        return String.join("\n",
                "너는 실시간으로 아이에게 바로 노출되는 신규 분기 초안의 자동 게이트다 - 뒤에 사람 검수가 없다.",
                "아이 질문 의미와 직접 연결되지 않거나, currentScene에 없는 소품·증거·약속을 만들거나,",
                "달콤한 냄새·조용한 소리·친절한 말만으로 안전을 결론내면 탈락이다.",
                "choiceCopy 3개가 하나의 고정 beat의 엄격한 바꿔쓰기가 아니거나, 대본·삽화 action·rejoin이 서로 다르면 탈락이다.",
                "인물·장소·소품·아동 안전을 모두 검사하고, 모든 점수가 2일 때만 pass=true로 하라.");
    }
}
