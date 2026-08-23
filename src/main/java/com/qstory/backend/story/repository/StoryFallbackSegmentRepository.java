package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryFallbackSegment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryFallbackSegmentRepository extends JpaRepository<StoryFallbackSegment, UUID> {

    List<StoryFallbackSegment> findByFamily_IdOrderByDisplayOrderAsc(String familyId);

    /** Bulk hydration path for StoryContentAssemblyService - one query for every segment of every fallback in a story. */
    List<StoryFallbackSegment> findByFamily_Anchor_Story_IdOrderByFamily_IdAscDisplayOrderAsc(String storyId);
}
