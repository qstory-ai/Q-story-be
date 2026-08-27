package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryVisualReferencePack;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryVisualReferencePackRepository extends JpaRepository<StoryVisualReferencePack, String> {

    /** LiveBranchExecutionWorker.buildImagePrompt()가 imageBrief.characters(정규화된 라벨)로 조회. */
    List<StoryVisualReferencePack> findByStory_IdAndLabelIn(String storyId, Collection<String> labels);
}
