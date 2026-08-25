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

/** story-registry.mjs의 Java 포팅본: 선행조건 게이팅, 컨텍스트 결정, contract 오류 처리. */
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

    /**
     * primarySpeakerId/allowedSpeakerIds는 sceneId와 일치하는 anchor에서 가져온다 (companion chat은
     * anchor 단위가 아니라 scene 단위로 범위가 정해지지만, 그래도 허용된 voice/speaker 집합이 필요하다).
     * forbiddenKnowledge는 현재 scene까지의 anchor만이 아니라 스토리 전체의 모든 anchor를 합집합으로
     * 묶는다 - 이 데이터 모델에서 scene 순서를 재구성하는 것보다 단순하고, 더 안전하기도 하다
     * (앞으로 나올 스포일러뿐 아니라 스토리 안의 모든 스포일러로부터 보호한다).
     */
    public record ResolvedCompanionContext(
            StoryManifest story,
            String sceneId,
            String primarySpeakerId,
            List<String> allowedSpeakerIds,
            List<String> forbiddenKnowledge,
            List<String> sttKeywords,
            StoryVersions versions) {}

    public ResolvedCompanionContext resolveCompanionChatContext(String storyId, String sceneId, CurrentUser callerOrNull) {
        StoryManifest story = registry.get(storyId);
        if (story == null) {
            throw ApiException.contractError(ErrorCode.STORY_NOT_REGISTERED, "요청한 작품이 등록되어 있지 않아요.");
        }
        entitlementService.assertAccessible(story, callerOrNull);
        if (!StoryAvailability.ACTIVE.contains(story.availability())) {
            throw ApiException.contractError(ErrorCode.STORY_NOT_AVAILABLE, "이 작품은 현재 대화 기능을 사용할 수 없어요.");
        }
        // 대부분의 scene에는 question anchor가 아예 없다 (HG의 10개 scene 중 5개만 가지고 있다) -
        // companion chat은 anchor가 있는 scene뿐 아니라 어디서든 동작해야 하므로, anchor가 없는 scene은
        // 400을 반환하는 대신 스토리의 narrator 음성으로 폴백한다.
        Anchor sceneAnchor = story.anchors().values().stream()
                .filter(candidate -> candidate.sceneId().equals(sceneId))
                .findFirst()
                .orElse(null);
        String primarySpeakerId;
        List<String> allowedSpeakerIds;
        if (sceneAnchor != null) {
            primarySpeakerId = sceneAnchor.primarySpeakerId();
            allowedSpeakerIds = sceneAnchor.allowedSpeakerIds();
        } else {
            CastEntry narrator = story.cast().values().stream()
                    .filter(entry -> "narrator".equals(entry.role()))
                    .findFirst()
                    .orElse(null);
            if (narrator == null) {
                throw ApiException.contractError(
                        ErrorCode.STORY_CONTEXT_NOT_ALLOWED, "현재 작품과 장면에 맞는 대화 위치를 찾지 못했어요.");
            }
            primarySpeakerId = narrator.speakerId();
            allowedSpeakerIds = List.of(narrator.speakerId());
        }
        List<String> forbiddenKnowledge = story.anchors().values().stream()
                .flatMap(anchor -> anchor.forbiddenKnowledge().stream())
                .distinct()
                .toList();
        // STT 힌트도 forbiddenKnowledge와 같은 이유로 scene 하나가 아니라 스토리 전체 anchor에서
        // 합집합으로 모은다 - companion chat은 특정 anchor에 묶이지 않으므로 이 scene에 국한된
        // 어휘만으로는 다른 장면 관련 질문의 인식률이 떨어진다.
        List<String> sttKeywords = story.anchors().values().stream()
                .flatMap(anchor -> anchor.sttKeywords().stream())
                .distinct()
                .toList();
        StoryVersions versions = new StoryVersions(
                story.routePromptVersion(), story.contentVersion(), story.routePolicyVersion(),
                story.responseTextNormalizationVersion());
        return new ResolvedCompanionContext(
                story, sceneId, primarySpeakerId, allowedSpeakerIds, forbiddenKnowledge, sttKeywords, versions);
    }

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

    /**
     * anchorId가 있으면(질문 응답 흐름) 그 anchor의 allowedSpeakerIds에 speakerId가 있어야만
     * 통과한다 - 기존 동작 그대로다. anchorId가 비어 있으면(대본 내레이션 - 특정 질문 지점에
     * 묶이지 않은 일반 대사) resolveCompanionChatContext의 "scene에 anchor가 없으면 폴백"과
     * 같은 이유의 관용이지만, 여기서는 내레이터로 좁히지 않고 스토리에 등록된 캐스트라면 누구든
     * 허용한다 - 대본은 여러 등장인물의 목소리를 실제로 쓰기 때문이다.
     */
    public ResolvedNarrationContext resolveNarrationContext(
            String storyId, String anchorId, String speakerId, CurrentUser callerOrNull) {
        StoryManifest story = registry.get(storyId);
        if (story == null || !StoryAvailability.ACTIVE.contains(story.availability())) {
            throw ApiException.contractError(
                    ErrorCode.NARRATION_STORY_NOT_ALLOWED, "This story is not allowed for dynamic narration");
        }
        entitlementService.assertAccessible(story, callerOrNull);

        StoryContext storyContext = null;
        if (!anchorId.isBlank()) {
            Anchor anchor = story.anchors().get(anchorId);
            if (anchor == null || !anchor.allowedSpeakerIds().contains(speakerId)) {
                throw ApiException.contractError(
                        ErrorCode.NARRATION_SPEAKER_NOT_ALLOWED, "This question speaker is not allowed");
            }
            storyContext = normalizeStoryContext(story, anchorId, anchor, List.of());
        }

        CastEntry cast = story.cast().values().stream()
                .filter(entry -> entry.speakerId().equals(speakerId))
                .findFirst()
                .orElse(null);
        if (cast == null) {
            throw ApiException.contractError(
                    ErrorCode.NARRATION_VOICE_NOT_ALLOWED, "This character voice is not registered");
        }
        return new ResolvedNarrationContext(story, storyContext, cast);
    }

    /** 호출자가 이미 선택한 이전 family들을 기준으로 action family/concern choice를 필터링한다. */
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
