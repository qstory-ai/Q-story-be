package com.qstory.backend.story.repository;
import com.qstory.backend.story.ConcernChoice;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.story.CastEntry;
import com.qstory.backend.story.Anchor;
import com.qstory.backend.story.ActionFamily;

import com.qstory.backend.story.entity.StoryActionFamily;
import com.qstory.backend.story.entity.StoryAnchor;
import com.qstory.backend.story.entity.StoryCast;
import com.qstory.backend.story.entity.Story;
import com.qstory.backend.story.repository.StoryActionFamilyRepository;
import com.qstory.backend.story.repository.StoryAnchorRepository;
import com.qstory.backend.story.repository.StoryCastRepository;
import com.qstory.backend.story.repository.StoryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Wraps the four story-content JPA repositories, assembling their rows into the StoryManifest domain shape StoryRegistry caches. */
@Component
public class StoryContentRepository {

    private final StoryRepository storyRepository;
    private final StoryAnchorRepository anchorRepository;
    private final StoryActionFamilyRepository familyRepository;
    private final StoryCastRepository castRepository;

    public StoryContentRepository(
            StoryRepository storyRepository, StoryAnchorRepository anchorRepository,
            StoryActionFamilyRepository familyRepository, StoryCastRepository castRepository) {
        this.storyRepository = storyRepository;
        this.anchorRepository = anchorRepository;
        this.familyRepository = familyRepository;
        this.castRepository = castRepository;
    }

    public List<StoryManifest> loadAll() {
        List<StoryManifest> stories = new ArrayList<>();
        for (Story storyEntity : storyRepository.findAll()) {
            stories.add(toDomain(storyEntity));
        }
        return stories;
    }

    private StoryManifest toDomain(Story storyEntity) {
        Map<String, Anchor> anchors = new LinkedHashMap<>();
        for (StoryAnchor anchorEntity : anchorRepository.findByStory_Id(storyEntity.getId())) {
            anchors.put(anchorEntity.getId(), toDomain(anchorEntity));
        }
        Map<String, CastEntry> cast = new LinkedHashMap<>();
        for (StoryCast castEntity : castRepository.findByStory_Id(storyEntity.getId())) {
            cast.put(castEntity.getCastTag(), toDomain(castEntity));
        }
        return new StoryManifest(
                storyEntity.getId(), storyEntity.getSlug(), storyEntity.getTitle(), storyEntity.getContentVersion(),
                storyEntity.getAvailability(), storyEntity.getRoutePromptVersion(),
                storyEntity.getRoutePolicyVersion(), storyEntity.getResponseTextNormalizationVersion(),
                anchors, storyEntity.getCastVersion(), cast, storyEntity.isRequiresEntitlement());
    }

    private Anchor toDomain(StoryAnchor anchorEntity) {
        List<ActionFamily> families = familyRepository.findByAnchor_IdOrderByDisplayOrderAsc(anchorEntity.getId())
                .stream()
                .map(this::toDomain)
                .toList();
        ConcernChoice concernChoice = anchorEntity.getConcernChoiceFamilyIds() == null
                ? null
                : new ConcernChoice(anchorEntity.getConcernChoiceFamilyIds(), anchorEntity.getConcernChoiceResponseText());
        return new Anchor(
                anchorEntity.getSlot(), anchorEntity.getSceneId(), anchorEntity.getSummary(),
                anchorEntity.getPrimarySpeakerId(), anchorEntity.getAllowedSpeakerIds(), anchorEntity.getSttKeywords(),
                anchorEntity.getDefaultFallbackFamilyId(), anchorEntity.getDefaultRejoinAt(), concernChoice,
                anchorEntity.getForbiddenKnowledge(), families);
    }

    private ActionFamily toDomain(StoryActionFamily familyEntity) {
        return new ActionFamily(
                familyEntity.getId(), familyEntity.getMeaning(), familyEntity.getAcknowledgementText(),
                familyEntity.getReportSummary(), familyEntity.getBridgeAudioId(), familyEntity.getBranchAssetId(),
                familyEntity.getRequiresPriorFamilyIds());
    }

    private CastEntry toDomain(StoryCast castEntity) {
        return new CastEntry(
                castEntity.getSpeakerId(), castEntity.getRole().toLowerCase(), castEntity.getDisplayName(),
                castEntity.getVoice(), castEntity.getProfile(), castEntity.getDirection(), castEntity.getSamePersonKey());
    }
}
