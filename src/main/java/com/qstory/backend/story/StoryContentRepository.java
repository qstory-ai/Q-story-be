package com.qstory.backend.story;

import com.qstory.backend.persistence.entity.StoryActionFamilyEntity;
import com.qstory.backend.persistence.entity.StoryAnchorEntity;
import com.qstory.backend.persistence.entity.StoryCastEntity;
import com.qstory.backend.persistence.entity.StoryEntity;
import com.qstory.backend.persistence.repository.StoryActionFamilyEntityRepository;
import com.qstory.backend.persistence.repository.StoryAnchorEntityRepository;
import com.qstory.backend.persistence.repository.StoryCastEntityRepository;
import com.qstory.backend.persistence.repository.StoryEntityRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Wraps the four story-content JPA repositories: entity->domain assembly for StoryRegistry, plus save helpers for StoryContentSeeder. */
@Component
public class StoryContentRepository {

    private final StoryEntityRepository storyRepository;
    private final StoryAnchorEntityRepository anchorRepository;
    private final StoryActionFamilyEntityRepository familyRepository;
    private final StoryCastEntityRepository castRepository;

    public StoryContentRepository(
            StoryEntityRepository storyRepository, StoryAnchorEntityRepository anchorRepository,
            StoryActionFamilyEntityRepository familyRepository, StoryCastEntityRepository castRepository) {
        this.storyRepository = storyRepository;
        this.anchorRepository = anchorRepository;
        this.familyRepository = familyRepository;
        this.castRepository = castRepository;
    }

    public List<Story> loadAll() {
        List<Story> stories = new ArrayList<>();
        for (StoryEntity storyEntity : storyRepository.findAll()) {
            stories.add(toDomain(storyEntity));
        }
        return stories;
    }

    public boolean existsStory(String storyId) {
        return storyRepository.existsById(storyId);
    }

    public StoryEntity saveStory(StoryEntity story) {
        return storyRepository.save(story);
    }

    public StoryAnchorEntity saveAnchor(StoryAnchorEntity anchor) {
        return anchorRepository.save(anchor);
    }

    public StoryActionFamilyEntity saveActionFamily(StoryActionFamilyEntity family) {
        return familyRepository.save(family);
    }

    public StoryCastEntity saveCast(StoryCastEntity cast) {
        return castRepository.save(cast);
    }

    private Story toDomain(StoryEntity storyEntity) {
        Map<String, Anchor> anchors = new LinkedHashMap<>();
        for (StoryAnchorEntity anchorEntity : anchorRepository.findByStory_Id(storyEntity.getId())) {
            anchors.put(anchorEntity.getId(), toDomain(anchorEntity));
        }
        Map<String, CastEntry> cast = new LinkedHashMap<>();
        for (StoryCastEntity castEntity : castRepository.findByStory_Id(storyEntity.getId())) {
            cast.put(castEntity.getCastTag(), toDomain(castEntity));
        }
        return new Story(
                storyEntity.getId(), storyEntity.getSlug(), storyEntity.getTitle(), storyEntity.getContentVersion(),
                storyEntity.getAvailability().name(), storyEntity.getRoutePromptVersion(),
                storyEntity.getRoutePolicyVersion(), storyEntity.getResponseTextNormalizationVersion(),
                anchors, storyEntity.getCastVersion(), cast);
    }

    private Anchor toDomain(StoryAnchorEntity anchorEntity) {
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

    private ActionFamily toDomain(StoryActionFamilyEntity familyEntity) {
        return new ActionFamily(
                familyEntity.getId(), familyEntity.getMeaning(), familyEntity.getAcknowledgementText(),
                familyEntity.getReportSummary(), familyEntity.getBridgeAudioId(), familyEntity.getBranchAssetId(),
                familyEntity.getRequiresPriorFamilyIds());
    }

    private CastEntry toDomain(StoryCastEntity castEntity) {
        return new CastEntry(
                castEntity.getSpeakerId(), castEntity.getRole().name().toLowerCase(), castEntity.getDisplayName(),
                castEntity.getVoice(), castEntity.getProfile(), castEntity.getDirection(), castEntity.getSamePersonKey());
    }
}
