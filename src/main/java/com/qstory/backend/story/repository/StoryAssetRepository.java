package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryAsset;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryAssetRepository extends JpaRepository<StoryAsset, Long> {

    List<StoryAsset> findByStory_IdOrderBySlugAsc(String storyId);
}
