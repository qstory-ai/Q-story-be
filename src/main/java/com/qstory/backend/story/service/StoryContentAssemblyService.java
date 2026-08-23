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
 * An in-memory, request-hot cache of every story's full narrative content (scenes/segments/
 * fallbacks, imported by StoryImportService), reassembled into the same {@code {generatedContent,
 * packageData}} shape the frontend's build pipeline used to bundle statically - so
 * fe/q-story-web's buildStoryRuntimePackage() can consume this response with no reshaping.
 *
 * <p>Reloaded once on boot (after {@link StoryRegistry}, via {@code @Order}) and again whenever
 * StoryImportService writes new content, exactly like StoryRegistry's own cache-then-reload
 * philosophy - this data changes by an explicit import, not per request.
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
                continue; // not yet imported - GET /v1/stories/{id}/content 404s until it is
            }
            assembled.put(story.getId(), assemble(story, scenes));
        }
        assembledByStoryId = Map.copyOf(assembled);
    }

    /** Null if this story hasn't been imported yet. */
    public ObjectNode get(String storyId) {
        return assembledByStoryId.get(storyId);
    }

    /**
     * Where the app should fetch this asset. A file re-rendered at runtime (see the narration
     * re-render pipeline) is stored remotely and carries an absolute URL; everything else is still
     * a path under the frontend's static root, served from the site root.
     */
    private String assetUrl(Story story, StoryAsset asset) {
        String file = asset.getFile();
        if (file.startsWith("http://") || file.startsWith("https://")) return file;
        // The frontend serves its static story files from the site root, so an asset stored as
        // "illustrations/x.jpg" is fetched from "/story/<slug>/illustrations/x.jpg".
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
        // story.yaml carries a few fields (targetAge/immutableEvents/forbiddenElements) with no
        // dedicated table yet - start from the cached raw copy, then overwrite every field that
        // has a live DB source of truth so an edit to those rows is never masked by a stale cache.
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

        // Assets travel with the content now. They used to reach the app only through
        // story-assets.generated.ts, baked into the bundle at build time - so a re-recorded line or
        // a swapped illustration could not reach a child without shipping a new frontend, which is
        // what made editing content in this database a half-measure.
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
                default -> { /* utterance/checkpoint/trace/sfx don't feed a scene-level derived list */ }
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

    /** Merges a cached raw JSON object (may be null, e.g. before the first import) into an ObjectNode. */
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
