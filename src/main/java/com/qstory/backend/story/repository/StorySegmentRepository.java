package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StorySegment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorySegmentRepository extends JpaRepository<StorySegment, UUID> {

    List<StorySegment> findByScene_IdOrderByDisplayOrderAsc(String sceneId);

    /** StoryContentAssemblyService를 위한 벌크 로딩 경로 - 한 스토리의 모든 장면에 속한 모든 세그먼트를 쿼리 한 번으로 가져온다. */
    List<StorySegment> findByScene_Story_IdOrderByScene_SequenceAscDisplayOrderAsc(String storyId);

    /**
     * StoryAuthoringService.editSegment()/restoreSegment(), NarrationRerenderService.rerender()가
     * 각자 손으로 다시 작성했던 "이 세그먼트가 정말 그 storyId 소유인가" 필터를 하나로 모았다.
     */
    Optional<StorySegment> findByIdAndScene_Story_Id(UUID id, String storyId);
}
