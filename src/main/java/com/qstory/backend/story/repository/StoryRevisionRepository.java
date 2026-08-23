package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryRevision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryRevisionRepository extends JpaRepository<StoryRevision, Long> {

    List<StoryRevision> findByStoryIdOrderByRevisionDesc(String storyId);

    Optional<StoryRevision> findFirstByStoryIdOrderByRevisionDesc(String storyId);

    List<StoryRevision> findByStoryIdAndTargetTypeAndTargetIdOrderByRevisionDesc(
            String storyId, com.qstory.backend.common.enums.RevisionTarget targetType, String targetId);
}
