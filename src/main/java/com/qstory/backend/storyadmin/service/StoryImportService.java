package com.qstory.backend.storyadmin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.choicecopy.ChoiceCopyVariant;
import com.qstory.backend.choicecopy.service.ChoiceCopyRegistry;
import com.qstory.backend.common.error.EdgeErrorCode;
import com.qstory.backend.common.error.EdgeException;
import com.qstory.backend.story.entity.Story;
import com.qstory.backend.story.entity.StoryActionFamily;
import com.qstory.backend.story.entity.StoryAnchor;
import com.qstory.backend.story.entity.StoryCast;
import com.qstory.backend.story.entity.StoryFallbackSegment;
import com.qstory.backend.story.entity.StoryScene;
import com.qstory.backend.story.entity.StorySegment;
import com.qstory.backend.story.repository.StoryActionFamilyRepository;
import com.qstory.backend.story.repository.StoryAnchorRepository;
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
 * Imports the already-compiled, already-QA-validated output of fe/q-story-web's
 * generate-story-package.mjs (generated-story-content.json + story-package.generated.json,
 * posted together as one body by the FE's import-story-to-backend.mjs script) into this backend's
 * Postgres schema. This backend has no filesystem access to the frontend repo's build output -
 * this HTTP import is the one hop that moves the compiled content across the two separate repos.
 * This is the single write path for a story's content - there is no seeder; the very first import
 * for a story id both creates and populates its row.
 *
 * <p>Every import is a full delete-and-reinsert of the target story's anchors/action-families
 * (and their choice-copy variants)/cast/scenes/segments (not a diff/upsert) - simple and safe at
 * the current single-story scale. Each fallback response's own fields
 * (requiresFamilyId/rejoinSlot/rejoinTarget) are set on the just-recreated StoryActionFamily row
 * in a second pass, since fallback content (generatedContent.fallbacks) ships in the same payload
 * as, but keyed independently from, the route-context-authored family rows above.
 */
@Service
public class StoryImportService {

    private final ObjectMapper objectMapper;
    private final StoryRepository storyRepository;
    private final StoryAnchorRepository anchorRepository;
    private final StoryActionFamilyRepository familyRepository;
    private final StoryCastRepository castRepository;
    private final StorySceneRepository sceneRepository;
    private final StorySegmentRepository segmentRepository;
    private final StoryFallbackSegmentRepository fallbackSegmentRepository;
    private final StoryRegistry storyRegistry;
    private final ChoiceCopyRegistry choiceCopyRegistry;
    private final StoryContentAssemblyService assemblyService;

    public StoryImportService(
            ObjectMapper objectMapper, StoryRepository storyRepository,
            StoryAnchorRepository anchorRepository, StoryActionFamilyRepository familyRepository,
            StoryCastRepository castRepository,
            StorySceneRepository sceneRepository, StorySegmentRepository segmentRepository,
            StoryFallbackSegmentRepository fallbackSegmentRepository,
            StoryRegistry storyRegistry, ChoiceCopyRegistry choiceCopyRegistry,
            StoryContentAssemblyService assemblyService) {
        this.objectMapper = objectMapper;
        this.storyRepository = storyRepository;
        this.anchorRepository = anchorRepository;
        this.familyRepository = familyRepository;
        this.castRepository = castRepository;
        this.sceneRepository = sceneRepository;
        this.segmentRepository = segmentRepository;
        this.fallbackSegmentRepository = fallbackSegmentRepository;
        this.storyRegistry = storyRegistry;
        this.choiceCopyRegistry = choiceCopyRegistry;
        this.assemblyService = assemblyService;
    }

    public record ImportResult(
            String storyId, int scenesImported, int segmentsImported, int fallbacksImported,
            int fallbackSegmentsImported) {}

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
        // story.yaml fields with no dedicated table yet (targetAge/immutableEvents/forbiddenElements) -
        // StoryContentAssemblyService overlays this with the live DB fields it does own.
        extras.put("story", toMap(storyNode));
        extras.put("reportCopy", toMap(packageData.path("reportCopy")));
        extras.put("release", toMap(packageData.path("release")));
        extras.put("evaluation", toMap(packageData.path("evaluation")));
        story.setPackageExtras(extras);
        story = storyRepository.save(story);

        // DB-level ON DELETE CASCADE (StoryActionFamily -> StoryAnchor, StoryFallbackSegment ->
        // StoryActionFamily) removes every downstream row for this story's old anchors/families
        // automatically - no need to load/delete those separately.
        anchorRepository.deleteAll(anchorRepository.findByStory_Id(storyId));
        importAnchors(routeContext, story);

        castRepository.deleteAll(castRepository.findByStory_Id(storyId));
        importCast(cast, story);

        // DB-level ON DELETE CASCADE (see StorySegment) removes scene segment rows
        // automatically - no need to load/delete them here. Fallback segments are handled
        // per-family below since their StoryActionFamily owner isn't deleted on import.
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
            // save() merges (rather than persists) entities with a manually-assigned @Id, returning a
            // different managed instance - segments below must reference that returned instance, not
            // the draft one, or Hibernate sees the FK pointing at a transient object.
            StoryScene scene = sceneRepository.save(draftScene);
            sceneCount++;
            segmentCount += importSegments(sceneNode.path("segments"), segments ->
                    segmentRepository.save(StorySegment.builder()
                            .scene(scene)
                            .displayOrder(segments.order())
                            .kind(segments.kind())
                            .branchPoint(segments.isBranchPoint())
                            .payload(segments.payload())
                            .build()));
        }

        int fallbackCount = 0;
        int fallbackSegmentCount = 0;
        for (JsonNode fallbackNode : generatedContent.path("fallbacks")) {
            JsonNode rejoin = fallbackNode.path("rejoin");
            JsonNode requires = fallbackNode.path("requires");
            String familyId = requireText(fallbackNode, "id");
            StoryActionFamily family = familyRepository.findById(familyId)
                    .orElseThrow(() -> new EdgeException(EdgeErrorCode.INVALID_PAYLOAD));
            family.setRequiresFamilyId(requires.isTextual() ? requires.asText() : null);
            family.setRejoinSlot(rejoin.path("slot").asText(""));
            family.setRejoinTarget(rejoin.path("target").asText(""));
            familyRepository.save(family);
            fallbackCount++;
            // The family row itself was just freshly recreated above (importAnchors), so it never
            // has pre-existing fallback segments - this is a no-op on every import, kept only so a
            // future move away from full anchor replacement doesn't silently leave stale segments.
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

        // storyRegistry/choiceCopyRegistry must reload before assemblyService, which reads through
        // storyRegistry.get() to build each scene's routeContext/cast JSON.
        storyRegistry.reload();
        choiceCopyRegistry.reload();
        assemblyService.reload();
        return new ImportResult(storyId, sceneCount, segmentCount, fallbackCount, fallbackSegmentCount);
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

    /** Shared per-segment loop for both scene.segments[] and fallback.segments[] - identical shape, different owner. */
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
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private JsonNode requireObject(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isObject()) {
            throw new EdgeException(EdgeErrorCode.INVALID_PAYLOAD);
        }
        return value;
    }

    private String requireText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new EdgeException(EdgeErrorCode.INVALID_PAYLOAD);
        }
        return value;
    }
}
