package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryFallbackSegment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryFallbackSegmentRepository extends JpaRepository<StoryFallbackSegment, UUID> {

    List<StoryFallbackSegment> findByFamily_IdOrderByDisplayOrderAsc(String familyId);

    /** StoryContentAssemblyService를 위한 벌크 로딩 경로 - 한 스토리의 모든 fallback에 속한 모든 세그먼트를 쿼리 한 번으로 가져온다. */
    List<StoryFallbackSegment> findByFamily_Anchor_Story_IdOrderByFamily_IdAscDisplayOrderAsc(String storyId);
}
