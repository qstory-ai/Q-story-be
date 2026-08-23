package com.qstory.backend.story.service;
import com.qstory.backend.story.ActionFamily;
import com.qstory.backend.story.ConcernChoice;
import com.qstory.backend.story.StoryContext;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.story.StoryVersions;
import com.qstory.backend.story.CastEntry;
import com.qstory.backend.story.Anchor;

import com.qstory.backend.common.enums.StoryAvailability;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.entitlement.service.EntitlementService;
import com.qstory.backend.identity.security.CurrentUser;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Java port of story-registry.mjs: prerequisite gating, context resolution, contract errors. */
@Service
public class StoryRegistryService {

    private final StoryRegistry registry;
    private final EntitlementService entitlementService;

    public StoryRegistryService(StoryRegistry registry, EntitlementService entitlementService) {
        this.registry = registry;
        this.entitlementService = entitlementService;
    }

    public record ResolvedQuestionContext(
            String storyId,
            String sceneId,
            String anchorId,
            int questionRound,
            String sourceMimeType,
            List<String> priorActionFamilyIds,
            boolean guaranteeAgencyChoice,
            StoryManifest story,
            StoryContext storyContext) {}

    public record ResolvedNarrationContext(StoryManifest story, StoryContext storyContext, CastEntry cast) {}

    public ResolvedQuestionContext resolveStoryQuestionContext(
            String storyId,
            String sceneId,
            String anchorId,
            int questionRound,
            String sourceMimeType,
            List<String> priorActionFamilyIds,
            boolean guaranteeAgencyChoice,
            CurrentUser callerOrNull) {
        StoryManifest story = registry.get(storyId);
        if (story == null) {
            throw ApiException.contractError(
                    ErrorCode.STORY_NOT_REGISTERED, "요청한 작품이 등록되어 있지 않아요.");
        }
        entitlementService.assertAccessible(story, callerOrNull);
        if (!StoryAvailability.ACTIVE.contains(story.availability())) {
            throw ApiException.contractError(
                    ErrorCode.STORY_NOT_AVAILABLE, "이 작품은 현재 질문 기능을 사용할 수 없어요.");
        }
        Anchor anchor = story.anchors().get(anchorId);
        if (anchor == null || !anchor.sceneId().equals(sceneId)) {
            throw ApiException.contractError(
                    ErrorCode.STORY_CONTEXT_NOT_ALLOWED, "현재 작품과 장면에 맞는 질문 위치를 찾지 못했어요.");
        }
        List<String> priors = priorActionFamilyIds == null ? List.of() : priorActionFamilyIds;
        return new ResolvedQuestionContext(
                storyId, sceneId, anchorId, questionRound, sourceMimeType, priors, guaranteeAgencyChoice,
                story, normalizeStoryContext(story, anchorId, anchor, priors));
    }

    public ResolvedNarrationContext resolveNarrationContext(
            String storyId, String anchorId, String speakerId, CurrentUser callerOrNull) {
        StoryManifest story = registry.get(storyId);
        if (story == null || !StoryAvailability.ACTIVE.contains(story.availability())) {
            throw ApiException.contractError(
                    ErrorCode.NARRATION_STORY_NOT_ALLOWED, "This story is not allowed for dynamic narration");
        }
        entitlementService.assertAccessible(story, callerOrNull);
        Anchor anchor = story.anchors().get(anchorId);
        if (anchor == null || !anchor.allowedSpeakerIds().contains(speakerId)) {
            throw ApiException.contractError(
                    ErrorCode.NARRATION_SPEAKER_NOT_ALLOWED, "This question speaker is not allowed");
        }
        CastEntry cast = story.cast().values().stream()
                .filter(entry -> entry.speakerId().equals(speakerId))
                .findFirst()
                .orElse(null);
        if (cast == null) {
            throw ApiException.contractError(
                    ErrorCode.NARRATION_VOICE_NOT_ALLOWED, "This character voice is not registered");
        }
        return new ResolvedNarrationContext(story, normalizeStoryContext(story, anchorId, anchor, List.of()), cast);
    }

    /** Filters action families/concern choice by which prior families the caller already chose. */
    public StoryContext normalizeStoryContext(StoryManifest story, String anchorId, Anchor anchor, List<String> priorActionFamilyIds) {
        Set<String> priors = new LinkedHashSet<>(priorActionFamilyIds);
        List<ActionFamily> actionFamilies = anchor.actionFamilies().stream()
                .filter(family -> family.requiresPriorFamilyIds().isEmpty()
                        || family.requiresPriorFamilyIds().stream().anyMatch(priors::contains))
                .toList();
        Set<String> availableFamilyIds = new LinkedHashSet<>(actionFamilies.stream().map(ActionFamily::id).toList());

        ConcernChoice concernChoice = null;
        if (anchor.concernChoice() != null) {
            Set<String> merged = new LinkedHashSet<>();
            anchor.concernChoice().familyIds().stream().filter(availableFamilyIds::contains).forEach(merged::add);
            actionFamilies.forEach(family -> merged.add(family.id()));
            List<String> familyIds = merged.stream().limit(3).toList();
            concernChoice = new ConcernChoice(familyIds, anchor.concernChoice().responseText());
        }

        StoryVersions versions = new StoryVersions(
                story.routePromptVersion(), story.contentVersion(), story.routePolicyVersion(),
                story.responseTextNormalizationVersion());

        return new StoryContext(
                anchor.slot(), anchor.sceneId(), anchor.summary(), anchor.primarySpeakerId(),
                anchor.allowedSpeakerIds(), anchor.sttKeywords(), anchor.defaultFallbackFamilyId(),
                anchor.defaultRejoinAt(), concernChoice, anchor.forbiddenKnowledge(), actionFamilies,
                anchorId, story.storyId(), anchor.defaultFallbackFamilyId(), anchor.defaultRejoinAt(), versions);
    }

    public Map<String, StoryContext> storyContextsByAnchor() {
        return registry.get(StoryRegistry.DEFAULT_BETA_STORY_ID) == null
                ? Map.of()
                : registry.get(StoryRegistry.DEFAULT_BETA_STORY_ID).anchors().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> normalizeStoryContext(
                                        registry.get(StoryRegistry.DEFAULT_BETA_STORY_ID),
                                        entry.getKey(), entry.getValue(), List.of())));
    }
}
