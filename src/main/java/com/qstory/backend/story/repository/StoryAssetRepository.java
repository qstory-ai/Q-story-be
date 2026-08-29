package com.qstory.backend.story.repository;

import com.qstory.backend.common.enums.AssetCategory;
import com.qstory.backend.story.entity.StoryAsset;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryAssetRepository extends JpaRepository<StoryAsset, Long> {

    List<StoryAsset> findByStory_IdOrderBySlugAsc(String storyId);

    /** NarrationRerenderService.narrationAssetFor()용 - 자산 하나만 찾으려고 스토리 전체 목록을 로드하지 않는다. */
    Optional<StoryAsset> findByStory_IdAndSlugAndCategory(String storyId, String slug, AssetCategory category);
}
