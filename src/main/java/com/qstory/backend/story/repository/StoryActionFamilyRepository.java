package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryActionFamily;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryActionFamilyRepository extends JpaRepository<StoryActionFamily, String> {

    List<StoryActionFamily> findByAnchor_IdOrderByDisplayOrderAsc(String anchorId);

    /** fallback 응답이 임포트되어 rejoinSlot이 설정된 family들 - StoryContentAssemblyService 참고. */
    List<StoryActionFamily> findByAnchor_Story_IdAndRejoinSlotIsNotNullOrderByAnchor_IdAscDisplayOrderAsc(
            String storyId);
}
