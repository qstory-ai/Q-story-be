package com.qstory.backend.storyadmin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.choicecopy.ChoiceCopyVariant;
import com.qstory.backend.choicecopy.service.ChoiceCopyRegistry;
import com.qstory.backend.common.enums.FamilyOrigin;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.JacksonConversion;
import com.qstory.backend.story.entity.Story;
import com.qstory.backend.story.entity.StoryActionFamily;
import com.qstory.backend.story.entity.StoryAnchor;
import com.qstory.backend.story.entity.StoryCast;
import com.qstory.backend.story.entity.StoryFallbackSegment;
import com.qstory.backend.story.entity.StoryScene;
import com.qstory.backend.story.entity.StorySegment;
import com.qstory.backend.story.repository.StoryActionFamilyRepository;
import com.qstory.backend.story.repository.StoryAnchorRepository;
import com.qstory.backend.common.enums.AssetCategory;
import com.qstory.backend.story.entity.StoryAsset;
import com.qstory.backend.story.entity.RoutePrompt;
import com.qstory.backend.story.entity.RoutePromptStage;
import com.qstory.backend.story.entity.StoryVisualReferencePack;
import com.qstory.backend.story.repository.RoutePromptRepository;
import com.qstory.backend.story.repository.RoutePromptStageRepository;
import com.qstory.backend.story.repository.StoryVisualReferencePackRepository;
import com.qstory.backend.common.enums.RevisionOperation;
import com.qstory.backend.common.enums.RevisionTarget;
import com.qstory.backend.common.enums.RoutePromptStageKind;
import com.qstory.backend.common.enums.VisualReferenceKind;
import com.qstory.backend.story.service.RoutePromptService;
import com.qstory.backend.story.repository.StoryAssetRepository;
import com.qstory.backend.story.repository.StoryCastRepository;
import com.qstory.backend.story.repository.StoryFallbackSegmentRepository;
import com.qstory.backend.story.repository.StoryRepository;
import com.qstory.backend.story.repository.StorySceneRepository;
import com.qstory.backend.story.repository.StorySegmentRepository;
import com.qstory.backend.story.service.StoryContentAssemblyService;
import com.qstory.backend.story.service.StoryRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * fe/q-story-web의 generate-story-package.mjs가 만들어낸, 이미 컴파일되고 이미 QA 검증까지 끝난
 * 결과물(generated-story-content.json + story-package.generated.json을 FE의
 * import-story-to-backend.mjs 스크립트가 하나의 본문으로 함께 전송한 것)을 이 백엔드의 Postgres
 * 스키마에 임포트한다. 이 백엔드는 프론트엔드 레포의 빌드 출력물에 파일시스템으로 접근할 수 없으므로,
 * 이 HTTP 임포트가 컴파일된 콘텐츠를 서로 분리된 두 레포 사이에서 옮기는 유일한 경로다. 이것이 스토리
 * 콘텐츠의 유일한 쓰기 경로다 - 별도의 시더(seeder)는 없으며, 어떤 스토리 id에 대한 최초 임포트가
 * 해당 행을 생성함과 동시에 채워 넣는다.
 *
 * <p>모든 임포트는 대상 스토리의 anchor/action-family(그리고 그 choice-copy variant들)/cast/
 * scene/segment 전체를 삭제 후 재삽입하는 방식이다(diff/upsert 방식이 아니다) - 현재의
 * 단일 스토리 규모에서는 이 방식이 단순하고 안전하다. 각 fallback 응답 자체의 필드
 * (requiresFamilyId/rejoinSlot/rejoinTarget)는, 방금 새로 재생성된 StoryActionFamily 행에
 * 대해 2차 패스에서 설정된다. fallback 콘텐츠(generatedContent.fallbacks)는 위쪽의
 * route-context 기반으로 작성된 family 행들과 같은 payload로 전송되지만, 서로 독립적인 키로
 * 구분되기 때문이다.
 */
@Service
public class StoryImportService {

    private final ObjectMapper objectMapper;
    private final StoryRepository storyRepository;
    private final StoryAnchorRepository anchorRepository;
    private final StoryActionFamilyRepository familyRepository;
    private final StoryCastRepository castRepository;
    private final StoryAssetRepository assetRepository;
    private final RoutePromptRepository routePromptRepository;
    private final RoutePromptStageRepository routePromptStageRepository;
    private final StoryVisualReferencePackRepository visualReferencePackRepository;
    private final RoutePromptService routePromptService;
    private final StoryRevisionService revisionService;
    private final StorySceneRepository sceneRepository;
    private final StorySegmentRepository segmentRepository;
    private final StoryFallbackSegmentRepository fallbackSegmentRepository;
    private final StoryRegistry storyRegistry;
    private final ChoiceCopyRegistry choiceCopyRegistry;
    private final StoryContentAssemblyService assemblyService;

    public StoryImportService(
            ObjectMapper objectMapper, StoryRepository storyRepository,
            StoryAnchorRepository anchorRepository, StoryActionFamilyRepository familyRepository,
            StoryCastRepository castRepository, StoryAssetRepository assetRepository,
            RoutePromptRepository routePromptRepository, RoutePromptStageRepository routePromptStageRepository,
            StoryVisualReferencePackRepository visualReferencePackRepository, RoutePromptService routePromptService,
            StoryRevisionService revisionService,
            StorySceneRepository sceneRepository, StorySegmentRepository segmentRepository,
            StoryFallbackSegmentRepository fallbackSegmentRepository,
            StoryRegistry storyRegistry, ChoiceCopyRegistry choiceCopyRegistry,
            StoryContentAssemblyService assemblyService) {
        this.objectMapper = objectMapper;
        this.storyRepository = storyRepository;
        this.anchorRepository = anchorRepository;
        this.familyRepository = familyRepository;
        this.castRepository = castRepository;
        this.assetRepository = assetRepository;
        this.routePromptRepository = routePromptRepository;
        this.routePromptStageRepository = routePromptStageRepository;
        this.visualReferencePackRepository = visualReferencePackRepository;
        this.routePromptService = routePromptService;
        this.revisionService = revisionService;
        this.sceneRepository = sceneRepository;
        this.segmentRepository = segmentRepository;
        this.fallbackSegmentRepository = fallbackSegmentRepository;
        this.storyRegistry = storyRegistry;
        this.choiceCopyRegistry = choiceCopyRegistry;
        this.assemblyService = assemblyService;
    }

    public record ImportResult(
            String storyId, int scenesImported, int segmentsImported, int fallbacksImported,
            int fallbackSegmentsImported, int assetsImported) {}

    @Transactional
    public ImportResult importStory(JsonNode body) {
        JsonNode generatedContent = requireObject(body, "generatedContent");
        JsonNode packageData = requireObject(body, "packageData");
        JsonNode storyNode = requireObject(packageData, "story");
        JsonNode routeContext = requireObject(packageData, "routeContext");
        JsonNode cast = requireObject(packageData, "cast");

        String storyId = requireText(generatedContent.path("story"), "id");
        Story story = storyRepository.findById(storyId).orElseGet(() -> Story.builder().id(storyId).build());
        story.setSlug(requireText(storyNode, "slug"));
        story.setTitle(requireText(storyNode, "title"));
        story.setContentVersion(requireText(storyNode, "contentVersion"));
        story.setAvailability(requireText(storyNode, "availability"));
        story.setRoutePromptVersion(requireText(routeContext, "routePromptVersion"));
        story.setRoutePolicyVersion(requireText(routeContext, "routePolicyVersion"));
        story.setResponseTextNormalizationVersion(requireText(routeContext, "responseTextNormalizationVersion"));
        story.setCastVersion(requireText(cast, "castVersion"));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("source", toMap(generatedContent.path("source")));
        // 아직 전용 테이블이 없는 story.yaml 필드들(targetAge/immutableEvents/forbiddenElements) -
        // StoryContentAssemblyService가 자신이 소유한 DB의 실시간 필드들로 이 위에 덧씌운다.
        extras.put("story", toMap(storyNode));
        extras.put("reportCopy", toMap(packageData.path("reportCopy")));
        extras.put("release", toMap(packageData.path("release")));
        extras.put("evaluation", toMap(packageData.path("evaluation")));
        // 런타임 데이터가 아니라 저작(authoring) 메타데이터이므로, 아무도 조회하지 않을 전용
        // 테이블을 만드는 대신 위의 세 항목과 함께 extras에 실어 보낸다 - 그래도 어쨌든 지금은
        // DB 안에 있다.
        extras.put("qaContract", toMap(packageData.path("qaContract")));
        extras.put("references", toMap(packageData.path("references")));
        story.setPackageExtras(extras);
        story = storyRepository.save(story);

        // LiveBranchExecutionWorker가 사람 검수 없이 실시간으로 커밋한 origin=LIVE_GENERATED
        // family(그리고 그 fallback segment/삽화 asset)는 재임포트가 절대 건드리면 안 된다. 그래서
        // 예전처럼 anchor를 통째로 delete-cascade(-> family 전부 cascade 삭제)하지 않는다: anchor는
        // id로 업서트(save()가 이미 동일 id 행을 병합-갱신한다 - importCast/importAssets 주석 참고)
        // 하고, family는 origin=AUTHORED인 것만 먼저 지운 뒤 이 페이로드의 family로 새로 채운다.
        List<String> existingAnchorIds = anchorRepository.findByStory_Id(storyId).stream()
                .map(StoryAnchor::getId)
                .toList();
        if (!existingAnchorIds.isEmpty()) {
            familyRepository.deleteByAnchor_IdInAndOrigin(existingAnchorIds, FamilyOrigin.AUTHORED);
            familyRepository.flush();
        }
        importAnchors(routeContext, story);

        castRepository.deleteAll(castRepository.findByStory_Id(storyId));
        // 재삽입 전에 flush(): 하나의 트랜잭션 안에서 Hibernate는 모든 INSERT를 모든 DELETE보다
        // 앞에 실행하는데, StoryCast는 여기서 유일하게 생성된 UUID 키에 더해 비즈니스 유니크 키
        // (story_id, cast_tag)까지 가진 엔티티다 - 그래서 새 행이, 아직 삭제되지 않은 기존 행과
        // 그 제약 조건에서 충돌한다. 나머지 엔티티들은 할당된 문자열 id를 사용하며, 이 경우 재임포트는
        // 삽입이 아니라 병합(merge)된다. 이 flush가 없으면 스토리를 두 번 임포트할 때마다 항상
        // 실패했다.
        castRepository.flush();
        importCast(cast, story);

        // asset은 family처럼 origin 컬럼이 없으므로(StoryAsset.familyId는 조인이 아니라 평범한
        // 문자열 컬럼), LIVE_GENERATED family에 속한 삽화(BRANCH_ART, familyId로 연결됨)만 골라
        // 삭제 대상에서 뺀다 - 그 외 asset(권위 있는 SCENE_ART/BRANCH_ART/BRIDGE/NARRATION 전부)은
        // 예전처럼 전부 지우고 이 페이로드로 다시 채운다. 재삽입 전에 flush하는 이유는 위 cast와
        // 동일하다(identity 키 + 비즈니스 유니크 키 (story_id, slug)).
        List<String> liveGeneratedFamilyIds = familyRepository
                .findByAnchor_Story_IdAndOrigin(storyId, FamilyOrigin.LIVE_GENERATED).stream()
                .map(StoryActionFamily::getId)
                .toList();
        List<StoryAsset> assetsToDelete = assetRepository.findByStory_IdOrderBySlugAsc(storyId).stream()
                .filter(asset -> asset.getFamilyId() == null || !liveGeneratedFamilyIds.contains(asset.getFamilyId()))
                .toList();
        assetRepository.deleteAll(assetsToDelete);
        assetRepository.flush();
        int assetCount = importAssets(packageData.path("assets"), story);
        importRoutePrompt(packageData.path("prompt"));
        // 프론트 콘텐츠 빌드 파이프라인이 아직 이 두 섹션을 만들어 보내지 않을 수 있다(Phase 2
        // §1/§4의 backend-only 시점 - StoryImportServiceTest 참고) - 그 경우 조용히 건너뛰고 SQL
        // 시드 마이그레이션이 채운 행을 그대로 둔다.
        importVisualReferencePacks(packageData.path("visualReferencePacks"), story);

        // DB 레벨의 ON DELETE CASCADE(StorySegment 참고)가 scene segment 행들을 자동으로
        // 삭제하므로, 여기서 따로 로드해서 삭제할 필요가 없다. fallback segment는 그 소유자인
        // StoryActionFamily가 임포트 시 삭제되지 않으므로, 아래에서 family 단위로 별도 처리한다.
        sceneRepository.deleteAll(sceneRepository.findByStory_IdOrderBySequenceAsc(storyId));

        int sceneCount = 0;
        int segmentCount = 0;
        int sequence = 0;
        for (JsonNode sceneNode : generatedContent.path("scenes")) {
            StoryScene draftScene = StoryScene.builder()
                    .id(requireText(sceneNode, "id"))
                    .story(story)
                    .title(sceneNode.path("title").asText(""))
                    .sequence(sequence++)
                    .checkpointId(sceneNode.path("checkpointId").asText(""))
                    .build();
            // save()는 수동으로 할당된 @Id를 가진 엔티티에 대해서는 persist가 아니라 merge를
            // 수행하며, 이때 (원본과는) 다른 관리 인스턴스를 반환한다 - 아래의 segment들은 draft
            // 인스턴스가 아니라 이 반환된 인스턴스를 참조해야 한다. 그렇지 않으면 Hibernate가
            // FK가 transient 객체를 가리키는 것으로 인식한다.
            StoryScene scene = sceneRepository.save(draftScene);
            sceneCount++;
            segmentCount += importSegments(sceneNode.path("segments"), segments ->
                    segmentRepository.save(StorySegment.builder()
                            .scene(scene)
                            .displayOrder(segments.order())
                            .kind(segments.kind())
                            .branchPoint(segments.isBranchPoint())
                            .payload(segments.payload())
                            // 임포트는 콘텐츠 파일과 그로부터 렌더링된 오디오를 함께 전송하므로,
                            // 이 시점의 녹음은 정확히 이 대사와 일치한다. 이후 모든 비교는 이 값을
                            // 기준으로 어떤 것을 다시 녹음해야 하는지 판단한다.
                            .narrationText(
                                    "utterance".equals(segments.kind())
                                            ? (String) segments.payload().get("text")
                                            : null)
                            .build()));
        }

        // 아래 루프가 fallback마다 한 번씩 findById를 호출하지 않도록, 참조되는 family id를
        // 전부 모아 한 번에 조회해 맵으로 준비해 둔다.
        List<String> fallbackFamilyIds = new ArrayList<>();
        generatedContent.path("fallbacks").forEach(fallbackNode -> fallbackFamilyIds.add(requireText(fallbackNode, "id")));
        Map<String, StoryActionFamily> familiesById = new LinkedHashMap<>();
        familyRepository.findAllById(fallbackFamilyIds).forEach(family -> familiesById.put(family.getId(), family));

        int fallbackCount = 0;
        int fallbackSegmentCount = 0;
        for (JsonNode fallbackNode : generatedContent.path("fallbacks")) {
            JsonNode rejoin = fallbackNode.path("rejoin");
            JsonNode requires = fallbackNode.path("requires");
            String familyId = requireText(fallbackNode, "id");
            StoryActionFamily family = familiesById.get(familyId);
            if (family == null) {
                throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
            }
            family.setRequiresFamilyId(requires.isTextual() ? requires.asText() : null);
            family.setRejoinSlot(rejoin.path("slot").asText(""));
            family.setRejoinTarget(rejoin.path("target").asText(""));
            familyRepository.save(family);
            fallbackCount++;
            // family 행 자체는 바로 위(importAnchors)에서 방금 새로 재생성되었으므로, 기존에
            // 존재하던 fallback segment가 있을 수 없다 - 즉 매 임포트마다 아무 일도 하지 않는
            // no-op이지만, 나중에 anchor 전체 교체 방식을 벗어나게 되더라도 오래된 segment가
            // 조용히 남지 않도록 하기 위해 남겨둔다.
            fallbackSegmentRepository.deleteAll(fallbackSegmentRepository.findByFamily_IdOrderByDisplayOrderAsc(familyId));
            fallbackSegmentCount += importSegments(fallbackNode.path("segments"), segments ->
                    fallbackSegmentRepository.save(StoryFallbackSegment.builder()
                            .family(family)
                            .displayOrder(segments.order())
                            .kind(segments.kind())
                            .branchPoint(segments.isBranchPoint())
                            .payload(segments.payload())
                            .build()));
        }

        // storyRegistry/choiceCopyRegistry는 assemblyService보다 먼저 reload되어야 한다.
        // assemblyService는 storyRegistry.get()을 통해 각 scene의 routeContext/cast JSON을
        // 구성하기 때문이다.
        storyRegistry.reload();
        choiceCopyRegistry.reload();
        assemblyService.reload();
        // 파이프라인 임포트는 스토리 전체를 교체하므로, 이력에는 작성자 없이 하나의 항목으로
        // 남는다 - 그렇지 않으면 이력만 봐서는 임포트가 마치 아무 일도 없었던 것처럼 보이게 된다.
        revisionService.record(
                storyId, RevisionTarget.SCENE, storyId, RevisionOperation.IMPORT,
                null,
                Map.of("scenes", sceneCount, "segments", segmentCount, "fallbacks", fallbackCount,
                        "assets", assetCount, "contentVersion", story.getContentVersion()),
                null,
                "content:import");
        return new ImportResult(
                storyId, sceneCount, segmentCount, fallbackCount, fallbackSegmentCount, assetCount);
    }

    private void importAnchors(JsonNode routeContext, Story story) {
        var anchorsNode = routeContext.path("anchors");
        var fieldNames = anchorsNode.fieldNames();
        while (fieldNames.hasNext()) {
            String anchorId = fieldNames.next();
            JsonNode anchorNode = anchorsNode.path(anchorId);
            JsonNode concernChoice = anchorNode.path("concernChoice");
            StoryAnchor anchor = anchorRepository.save(StoryAnchor.builder()
                    .id(anchorId)
                    .story(story)
                    .slot(requireText(anchorNode, "slot"))
                    .sceneId(requireText(anchorNode, "sceneId"))
                    .summary(requireText(anchorNode, "summary"))
                    .primarySpeakerId(requireText(anchorNode, "primarySpeakerId"))
                    .allowedSpeakerIds(toStringList(anchorNode.path("allowedSpeakerIds")))
                    .sttKeywords(toStringList(anchorNode.path("sttKeywords")))
                    .defaultFallbackFamilyId(requireText(anchorNode, "defaultFallbackFamilyId"))
                    .defaultRejoinAt(requireText(anchorNode, "defaultRejoinAt"))
                    .forbiddenKnowledge(toStringList(anchorNode.path("forbiddenKnowledge")))
                    .concernChoiceFamilyIds(concernChoice.isMissingNode() ? null : toStringList(concernChoice.path("familyIds")))
                    .concernChoiceResponseText(concernChoice.isMissingNode() ? null : concernChoice.path("responseText").asText(null))
                    .build());

            int order = 0;
            for (JsonNode familyNode : anchorNode.path("actionFamilies")) {
                familyRepository.save(StoryActionFamily.builder()
                        .id(requireText(familyNode, "id"))
                        .anchor(anchor)
                        .meaning(requireText(familyNode, "meaning"))
                        .acknowledgementText(requireText(familyNode, "acknowledgementText"))
                        .reportSummary(requireText(familyNode, "reportSummary"))
                        .bridgeAudioId(requireText(familyNode, "bridgeAudioId"))
                        .branchAssetId(requireText(familyNode, "branchAssetId"))
                        .requiresPriorFamilyIds(toStringList(familyNode.path("requiresPriorFamilyIds")))
                        .displayOrder(order++)
                        .choiceCopyVariants(toChoiceCopyVariants(familyNode.path("choiceCopyVariants")))
                        .build());
            }
        }
    }

    private void importCast(JsonNode cast, Story story) {
        var speakersNode = cast.path("speakers");
        var fieldNames = speakersNode.fieldNames();
        while (fieldNames.hasNext()) {
            String castTag = fieldNames.next();
            JsonNode speakerNode = speakersNode.path(castTag);
            castRepository.save(StoryCast.builder()
                    .story(story)
                    .castTag(castTag)
                    .speakerId(requireText(speakerNode, "speakerId"))
                    .role(requireText(speakerNode, "role").toUpperCase())
                    .displayName(requireText(speakerNode, "displayName"))
                    .voice(requireText(speakerNode, "voice"))
                    .profile(requireText(speakerNode, "profile"))
                    .direction(requireText(speakerNode, "direction"))
                    .samePersonKey(speakerNode.path("samePersonKey").isMissingNode() ? null : speakerNode.path("samePersonKey").asText(null))
                    .build());
        }
    }

    private int importAssets(JsonNode assets, Story story) {
        if (!assets.isArray()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        for (JsonNode asset : assets) {
            JsonNode panel = asset.path("panel");
            assetRepository.save(StoryAsset.builder()
                    .story(story)
                    .slug(requireText(asset, "slug"))
                    .category(AssetCategory.valueOf(requireText(asset, "category")))
                    .file(requireText(asset, "file"))
                    .integrity(requireText(asset, "integrity"))
                    .familyId(asset.path("familyId").isMissingNode() ? null : asset.path("familyId").asText(null))
                    .panel(panel.isMissingNode() || panel.isNull() ? null : panel.asInt())
                    .build());
        }
        return assets.size();
    }

    /** 스토리 단위가 아니라 버전 단위로 upsert된다: 여러 스토리가 동일한 정책을 참조할 수 있기 때문이다. */
    private void importRoutePrompt(JsonNode prompt) {
        if (!prompt.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        String version = requireText(prompt, "version");
        RoutePrompt row = routePromptRepository.findByVersion(version)
                .orElseGet(() -> RoutePrompt.builder().version(version).build());
        row.setSystemText(joinLines(prompt.path("system")));
        row.setInstructionText(joinLines(prompt.path("instruction")));
        routePromptRepository.save(row);
        importRoutePromptStages(version, prompt.path("stages"));
        // 기존 버전을 다시 임포트하는 것이 정책을 그 자리에서 수정하는 방법이다 - 이 호출이 없으면
        // 재시작 전까지는 버전별 캐시에서 계속 이전 텍스트가 서빙된다(단일 호출용/3단계 캐시 모두).
        routePromptService.invalidate(version);
    }

    /**
     * Phase 2의 3단계 파이프라인(safety_scope_gate/route_classifier/content_generator) 전용
     * 프롬프트. 콘텐츠 빌드 파이프라인이 아직 packageData.prompt.stages를 만들어 보내지 않을 수
     * 있으므로(이 백엔드 작업 범위 밖 - 최종 보고 참고) 그 필드가 없으면 조용히 건너뛰고, 이미
     * 시드 마이그레이션이 채워 둔 route_prompt_stage 행을 그대로 둔다. 기대하는 모양:
     * {@code prompt.stages.{safety,classifier,generator} = {system: string[], examples: [{input, output}]}}.
     */
    private void importRoutePromptStages(String version, JsonNode stages) {
        if (!stages.isObject()) {
            return;
        }
        upsertStage(version, RoutePromptStageKind.SAFETY, stages.path("safety"));
        upsertStage(version, RoutePromptStageKind.CLASSIFIER, stages.path("classifier"));
        upsertStage(version, RoutePromptStageKind.GENERATOR, stages.path("generator"));
    }

    private void upsertStage(String version, RoutePromptStageKind stage, JsonNode stageNode) {
        if (!stageNode.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        RoutePromptStage row = routePromptStageRepository.findByRoutePromptVersionAndStage(version, stage)
                .orElseGet(() -> RoutePromptStage.builder().routePromptVersion(version).stage(stage).build());
        row.setSystemText(joinLines(stageNode.path("system")));
        row.setExamples(toExampleMaps(stageNode.path("examples")));
        routePromptStageRepository.save(row);
    }

    private List<Map<String, Object>> toExampleMaps(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> examples = new ArrayList<>();
        for (JsonNode example : arrayNode) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("input", toMap(example.path("input")));
            map.put("output", toMap(example.path("output")));
            examples.add(map);
        }
        return examples;
    }

    /**
     * StoryVisualReferencePack(§4) - 콘텐츠 빌드 파이프라인이 아직 이 섹션을 만들어 보내지 않을 수
     * 있으므로(이 백엔드 작업 범위 밖) 없으면 조용히 건너뛰고 시드 마이그레이션이 채운 행을 그대로
     * 둔다. 기대하는 모양: {@code packageData.visualReferencePacks = [{id, kind, label, immutableFacts: string[]}]}.
     */
    private void importVisualReferencePacks(JsonNode packs, Story story) {
        if (!packs.isArray()) {
            return;
        }
        for (JsonNode packNode : packs) {
            visualReferencePackRepository.save(StoryVisualReferencePack.builder()
                    .id(requireText(packNode, "id"))
                    .story(story)
                    .kind(VisualReferenceKind.valueOf(requireText(packNode, "kind").toUpperCase()))
                    .label(requireText(packNode, "label"))
                    .immutableFacts(toStringList(packNode.path("immutableFacts")))
                    .build());
        }
    }

    private String joinLines(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.isEmpty()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        return String.join(" ", toStringList(arrayNode));
    }

    private List<String> toStringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : arrayNode) {
            values.add(value.asText());
        }
        return values;
    }

    private List<ChoiceCopyVariant> toChoiceCopyVariants(JsonNode arrayNode) {
        if (!arrayNode.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(arrayNode, new TypeReference<List<ChoiceCopyVariant>>() {});
    }

    private record SegmentToSave(int order, String kind, boolean isBranchPoint, Map<String, Object> payload) {}

    /** scene.segments[]와 fallback.segments[] 양쪽에서 공유하는 세그먼트 단위 루프 - 형태는 동일하고 소유자만 다르다. */
    private int importSegments(JsonNode segmentsNode, java.util.function.Consumer<SegmentToSave> save) {
        int order = 0;
        int count = 0;
        for (JsonNode segmentNode : segmentsNode) {
            String kind = requireText(segmentNode, "kind");
            save.accept(new SegmentToSave(order++, kind, "interaction".equals(kind), payloadWithoutKind(segmentNode)));
            count++;
        }
        return count;
    }

    private Map<String, Object> payloadWithoutKind(JsonNode segmentNode) {
        Map<String, Object> map = objectMapper.convertValue(segmentNode, new TypeReference<LinkedHashMap<String, Object>>() {});
        map.remove("kind");
        return map;
    }

    private Map<String, Object> toMap(JsonNode node) {
        return JacksonConversion.toMap(objectMapper, node);
    }

    private JsonNode requireObject(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        return value;
    }

    private String requireText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw ApiException.contractError(ErrorCode.INVALID_PAYLOAD, "요청 형식이 올바르지 않아요.");
        }
        return value;
    }
}
