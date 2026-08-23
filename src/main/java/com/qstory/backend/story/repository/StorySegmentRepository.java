package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StorySegment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorySegmentRepository extends JpaRepository<StorySegment, UUID> {

    List<StorySegment> findByScene_IdOrderByDisplayOrderAsc(String sceneId);

    /** Bulk hydration path for StoryContentAssemblyService - one query for every segment of every scene in a story. */
    List<StorySegment> findByScene_Story_IdOrderByScene_SequenceAscDisplayOrderAsc(String storyId);
}
