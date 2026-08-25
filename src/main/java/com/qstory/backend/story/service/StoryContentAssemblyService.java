package com.qstory.backend.story.service;
import com.qstory.backend.story.ActionFamily;
import com.qstory.backend.story.CastEntry;
import com.qstory.backend.story.Anchor;
import com.qstory.backend.story.StoryManifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.story.entity.StoryActionFamily;
import com.qstory.backend.story.entity.Story;
import com.qstory.backend.story.entity.StoryFallbackSegment;
import com.qstory.backend.story.entity.StoryScene;
import com.qstory.backend.story.entity.StorySegment;
import com.qstory.backend.story.repository.StoryActionFamilyRepository;
import com.qstory.backend.story.repository.StoryRepository;
import com.qstory.backend.story.repository.StoryFallbackSegmentRepository;
import com.qstory.backend.story.repository.StorySceneRepository;
import com.qstory.backend.story.entity.StoryAsset;
import com.qstory.backend.story.repository.StoryAssetRepository;
import com.qstory.backend.story.repository.StorySegmentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모든 스토리의 전체 서사 콘텐츠(장면/세그먼트/fallback, StoryImportService가 임포트함)를 메모리에
 * 올려두는, 요청마다 즉시 사용되는 캐시다. 프론트엔드의 빌드 파이프라인이 정적으로 번들링할 때 쓰던
 * {@code {generatedContent, packageData}}와 동일한 형태로 재조립하므로, fe/q-story-web의
 * buildStoryRuntimePackage()가 이 응답을 별도 가공 없이 그대로 소비할 수 있다.
 *
 * <p>부팅 시 한 번({@code @Order}에 의해 {@link StoryRegistry} 이후) 로드되고, StoryImportService가
 * 새 콘텐츠를 기록할 때마다 다시 로드된다 - StoryRegistry 자체의 캐시 후 재로드 방식과 정확히 동일하게,
 * 이 데이터는 요청마다가 아니라 명시적인 임포트에 의해서만 바뀐다.
 */
@Component
@Order(2)
public class StoryContentAssemblyService implements ApplicationRunner {

    private final ObjectMapper objectMapper;
    private final StoryRepository storyRepository;
    private final StorySceneRepository sceneRepository;
    private final StorySegmentRepository segmentRepository;
    private final StoryActionFamilyRepository familyRepository;
    private final StoryFallbackSegmentRepository fallbackSegmentRepository;
    private final StoryRegistry storyRegistry;

    private final StoryAssetRepository assetRepository;

    private volatile Map<String, ObjectNode> assembledByStoryId = Map.of();

    public StoryContentAssemblyService(
            ObjectMapper objectMapper, StoryRepository storyRepository,
            StorySceneRepository sceneRepository, StorySegmentRepository segmentRepository,
            StoryActionFamilyRepository familyRepository,
            StoryFallbackSegmentRepository fallbackSegmentRepository,
            StoryAssetRepository assetRepository, StoryRegistry storyRegistry) {
        this.objectMapper = objectMapper;
        this.storyRepository = storyRepository;
        this.sceneRepository = sceneRepository;
        this.segmentRepository = segmentRepository;
        this.familyRepository = familyRepository;
        this.fallbackSegmentRepository = fallbackSegmentRepository;
        this.assetRepository = assetRepository;
        this.storyRegistry = storyRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    @Transactional(readOnly = true)
    public void reload() {
        Map<String, ObjectNode> assembled = new LinkedHashMap<>();
        for (Story story : storyRepository.findAll()) {
            List<StoryScene> scenes = sceneRepository.findByStory_IdOrderBySequenceAsc(story.getId());
            if (scenes.isEmpty()) {
                continue; // 아직 임포트되지 않음 - 임포트되기 전까지는 GET /v1/stories/{id}/content가 404를 반환한다
            }
            assembled.put(story.getId(), assemble(story, scenes));
        }
        assembledByStoryId = Map.copyOf(assembled);
    }

    /** 이 스토리가 아직 임포트되지 않았다면 null. */
    public ObjectNode get(String storyId) {
        return assembledByStoryId.get(storyId);
    }

    /**
     * 앱이 이 asset을 가져와야 할 위치. 런타임에 재렌더링된 파일(narration 재렌더링 파이프라인 참고)은
     * 원격에 저장되어 절대 URL을 가지며, 그 외 나머지는 여전히 프론트엔드 정적 루트 아래의 경로로,
     * 사이트 루트에서 서빙된다.
     */
    private String assetUrl(Story story, StoryAsset asset) {
        String file = asset.getFile();
        if (file.startsWith("http://") || file.startsWith("https://")) return file;
        // 프론트엔드는 정적 스토리 파일을 사이트 루트에서 서빙하므로, "illustrations/x.jpg"로 저장된
        // asset은 "/story/<slug>/illustrations/x.jpg"에서 가져오게 된다.
        return "/story/" + story.getSlug() + "/" + file;
    }

    private ObjectNode assemble(Story story, List<StoryScene> scenes) {
        Map<String, List<StorySegment>> segmentsByScene =
                segmentRepository.findByScene_Story_IdOrderByScene_SequenceAscDisplayOrderAsc(story.getId()).stream()
                        .collect(Collectors.groupingBy(
                                segment -> segment.getScene().getId(), LinkedHashMap::new, Collectors.toList()));
        List<StoryActionFamily> fallbackFamilies = familyRepository
                .findByAnchor_Story_IdAndRejoinSlotIsNotNullOrderByAnchor_IdAscDisplayOrderAsc(story.getId());
        Map<String, List<StoryFallbackSegment>> segmentsByFamily =
                fallbackSegmentRepository.findByFamily_Anchor_Story_IdOrderByFamily_IdAscDisplayOrderAsc(story.getId())
                        .stream()
                        .collect(Collectors.groupingBy(
                                segment -> segment.getFamily().getId(), LinkedHashMap::new, Collectors.toList()));

        Map<String, Object> extras = story.getPackageExtras() == null ? Map.of() : story.getPackageExtras();

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode generatedContent = root.putObject("generatedContent");
        generatedContent.put("schemaVersion", 1);
        generatedContent.set("source", objectMapper.valueToTree(extras.get("source")));
        ObjectNode storyNode = generatedContent.putObject("story");
        storyNode.put("id", story.getId());
        storyNode.put("title", story.getTitle());
        storyNode.put("contentVersion", story.getContentVersion());

        ArrayNode scenesArray = generatedContent.putArray("scenes");
        for (StoryScene scene : scenes) {
            scenesArray.add(sceneToJson(scene, segmentsByScene.getOrDefault(scene.getId(), List.of())));
        }
        ArrayNode fallbacksArray = generatedContent.putArray("fallbacks");
        for (StoryActionFamily family : fallbackFamilies) {
            fallbacksArray.add(fallbackToJson(family, segmentsByFamily.getOrDefault(family.getId(), List.of())));
        }

        ObjectNode packageData = root.putObject("packageData");
        packageData.put("schemaVersion", 1);
        ObjectNode packageStory = packageData.putObject("story");
        // story.yaml은 아직 전용 테이블이 없는 몇몇 필드(targetAge/immutableEvents/forbiddenElements)를
        // 가지고 있다 - 캐시된 원본 복사본에서 시작한 다음, 실제 DB가 source of truth인 필드들을 모두
        // 덮어써서, 그 row들에 대한 수정이 오래된 캐시에 의해 가려지는 일이 없도록 한다.
        setAll(packageStory, extras.get("story"));
        packageStory.put("schemaVersion", 1);
        packageStory.put("storyId", story.getId());
        packageStory.put("slug", story.getSlug());
        packageStory.put("title", story.getTitle());
        packageStory.put("contentVersion", story.getContentVersion());
        packageStory.put("entrySceneId", scenes.get(0).getId());
        packageStory.put("endingSceneId", scenes.get(scenes.size() - 1).getId());

        ObjectNode routeContext = packageData.putObject("routeContext");
        routeContext.put("schemaVersion", 1);
        routeContext.put("storyId", story.getId());
        routeContext.put("routePromptVersion", story.getRoutePromptVersion());
        routeContext.put("routePolicyVersion", story.getRoutePolicyVersion());
        routeContext.put("responseTextNormalizationVersion", story.getResponseTextNormalizationVersion());
        ObjectNode anchorsNode = routeContext.putObject("anchors");
        StoryManifest domainStory = storyRegistry.get(story.getId());
        if (domainStory != null) {
            domainStory.anchors().forEach((anchorId, anchor) -> anchorsNode.set(anchorId, anchorToJson(anchor)));
        }

        ObjectNode castNode = packageData.putObject("cast");
        castNode.put("schemaVersion", 1);
        castNode.put("storyId", story.getId());
        castNode.put("castVersion", story.getCastVersion());
        ObjectNode speakersNode = castNode.putObject("speakers");
        if (domainStory != null) {
            domainStory.cast().forEach((castTag, cast) -> speakersNode.set(castTag, castEntryToJson(cast)));
        }

        // 이제 asset은 콘텐츠와 함께 전달된다. 예전에는 story-assets.generated.ts를 통해서만 앱에 도달했고,
        // 이는 빌드 시점에 번들에 구워 넣는 방식이었다 - 그래서 다시 녹음한 대사나 교체된 삽화가 새 프론트엔드를
        // 배포하지 않고서는 아이에게 도달할 수 없었고, 이것이 바로 이 데이터베이스에서 콘텐츠를 수정하는 것을
        // 반쪽짜리 조치로 만드는 이유였다.
        ArrayNode assetsArray = packageData.putArray("assets");
        for (StoryAsset asset : assetRepository.findByStory_IdOrderBySlugAsc(story.getId())) {
            ObjectNode node = assetsArray.addObject();
            node.put("slug", asset.getSlug());
            node.put("category", asset.getCategory().name());
            node.put("url", assetUrl(story, asset));
            node.put("integrity", asset.getIntegrity());
            if (asset.getFamilyId() != null) node.put("familyId", asset.getFamilyId());
            if (asset.getPanel() != null) node.put("panel", asset.getPanel());
        }
        packageData.set("reportCopy", objectMapper.valueToTree(extras.get("reportCopy")));
        packageData.set("release", objectMapper.valueToTree(extras.get("release")));
        packageData.set("evaluation", objectMapper.valueToTree(extras.get("evaluation")));
        packageData.put("sourceDigest", sourceDigest(extras));

        return root;
    }

    private ObjectNode sceneToJson(StoryScene scene, List<StorySegment> segments) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", scene.getId());
        node.put("title", scene.getTitle());
        ArrayNode visuals = node.putArray("visuals");
        ArrayNode segmentsArray = node.putArray("segments");
        ArrayNode questionSlots = node.putArray("questionSlots");
        ArrayNode anchors = node.putArray("anchors");
        ArrayNode rejoins = node.putArray("rejoins");
        for (StorySegment segment : segments) {
            ObjectNode payload = (ObjectNode) objectMapper.valueToTree(segment.getPayload());
            segmentsArray.add(withKindFirst(segment.getKind(), payload));
            switch (segment.getKind()) {
                case "visual" -> visuals.add(payload.deepCopy());
                case "interaction" -> questionSlots.add(payload.path("slot").asText(""));
                case "anchor" -> anchors.add(payload.path("id").asText(""));
                case "rejoin" -> rejoins.add(payload.deepCopy());
                default -> { /* utterance/checkpoint/trace/sfx는 장면 단위로 파생된 목록에 포함되지 않는다 */ }
            }
        }
        node.put("checkpointId", scene.getCheckpointId());
        return node;
    }

    private ObjectNode fallbackToJson(StoryActionFamily family, List<StoryFallbackSegment> segments) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", family.getId());
        if (family.getRequiresFamilyId() == null) {
            node.putNull("requires");
        } else {
            node.put("requires", family.getRequiresFamilyId());
        }
        ArrayNode segmentsArray = node.putArray("segments");
        for (StoryFallbackSegment segment : segments) {
            ObjectNode payload = (ObjectNode) objectMapper.valueToTree(segment.getPayload());
            segmentsArray.add(withKindFirst(segment.getKind(), payload));
        }
        ObjectNode rejoin = node.putObject("rejoin");
        rejoin.put("slot", family.getRejoinSlot());
        rejoin.put("target", family.getRejoinTarget());
        return node;
    }

    private ObjectNode withKindFirst(String kind, ObjectNode payload) {
        ObjectNode full = objectMapper.createObjectNode();
        full.put("kind", kind);
        full.setAll(payload);
        return full;
    }

    private ObjectNode anchorToJson(Anchor anchor) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("slot", anchor.slot());
        node.put("sceneId", anchor.sceneId());
        node.put("summary", anchor.summary());
        node.put("primarySpeakerId", anchor.primarySpeakerId());
        ArrayNode allowedSpeakerIds = node.putArray("allowedSpeakerIds");
        anchor.allowedSpeakerIds().forEach(allowedSpeakerIds::add);
        ArrayNode sttKeywords = node.putArray("sttKeywords");
        anchor.sttKeywords().forEach(sttKeywords::add);
        node.put("defaultFallbackFamilyId", anchor.defaultFallbackFamilyId());
        node.put("defaultRejoinAt", anchor.defaultRejoinAt());
        if (anchor.concernChoice() != null) {
            ObjectNode concernChoice = node.putObject("concernChoice");
            ArrayNode familyIds = concernChoice.putArray("familyIds");
            anchor.concernChoice().familyIds().forEach(familyIds::add);
            concernChoice.put("responseText", anchor.concernChoice().responseText());
        }
        ArrayNode forbiddenKnowledge = node.putArray("forbiddenKnowledge");
        anchor.forbiddenKnowledge().forEach(forbiddenKnowledge::add);
        ArrayNode actionFamilies = node.putArray("actionFamilies");
        for (ActionFamily family : anchor.actionFamilies()) {
            ObjectNode familyNode = objectMapper.createObjectNode();
            familyNode.put("id", family.id());
            familyNode.put("meaning", family.meaning());
            familyNode.put("acknowledgementText", family.acknowledgementText());
            familyNode.put("reportSummary", family.reportSummary());
            familyNode.put("bridgeAudioId", family.bridgeAudioId());
            familyNode.put("branchAssetId", family.branchAssetId());
            if (!family.requiresPriorFamilyIds().isEmpty()) {
                ArrayNode requiresPriorFamilyIds = familyNode.putArray("requiresPriorFamilyIds");
                family.requiresPriorFamilyIds().forEach(requiresPriorFamilyIds::add);
            }
            actionFamilies.add(familyNode);
        }
        return node;
    }

    /** 캐시된 원본 JSON 객체(예: 첫 임포트 이전이라 null일 수 있음)를 ObjectNode에 병합한다. */
    private void setAll(ObjectNode target, Object rawMap) {
        if (rawMap instanceof Map<?, ?> map) {
            target.setAll((ObjectNode) objectMapper.valueToTree(map));
        }
    }

    private ObjectNode castEntryToJson(CastEntry cast) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("speakerId", cast.speakerId());
        node.put("role", cast.role());
        node.put("displayName", cast.displayName());
        node.put("voice", cast.voice());
        node.put("profile", cast.profile());
        node.put("direction", cast.direction());
        if (cast.samePersonKey() != null) {
            node.put("samePersonKey", cast.samePersonKey());
        }
        return node;
    }

    private String sourceDigest(Map<String, Object> extras) {
        if (extras.get("source") instanceof Map<?, ?> source && source.get("digest") instanceof String digest) {
            return digest;
        }
        return "";
    }
}
