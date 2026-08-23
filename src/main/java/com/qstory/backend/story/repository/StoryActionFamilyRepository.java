package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryActionFamily;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryActionFamilyRepository extends JpaRepository<StoryActionFamily, String> {

    List<StoryActionFamily> findByAnchor_IdOrderByDisplayOrderAsc(String anchorId);

    /** Families whose fallback response has been imported (rejoinSlot set) - see StoryContentAssemblyService. */
    List<StoryActionFamily> findByAnchor_Story_IdAndRejoinSlotIsNotNullOrderByAnchor_IdAscDisplayOrderAsc(
            String storyId);
}
