package com.qstory.backend.story.repository;

import com.qstory.backend.common.enums.FamilyOrigin;
import com.qstory.backend.story.entity.StoryActionFamily;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoryActionFamilyRepository extends JpaRepository<StoryActionFamily, String> {

    List<StoryActionFamily> findByAnchor_IdOrderByDisplayOrderAsc(String anchorId);

    /**
     * StoryContentRepository가 스토리 하나의 모든 family를 앵커별 N+1 없이 한 번에 읽는 경로. 트랜잭션
     * 밖(부팅 시 ApplicationRunner)에서도 anchor id를 읽을 수 있게 anchor를 함께 가져온다.
     */
    @Query("select f from StoryActionFamily f join fetch f.anchor a "
            + "where a.story.id = :storyId order by a.id, f.displayOrder")
    List<StoryActionFamily> findAllByStoryIdWithAnchor(@Param("storyId") String storyId);

    /** fallback 응답이 임포트되어 rejoinSlot이 설정된 family들 - StoryContentAssemblyService 참고. */
    List<StoryActionFamily> findByAnchor_Story_IdAndRejoinSlotIsNotNullOrderByAnchor_IdAscDisplayOrderAsc(
            String storyId);

    /**
     * StoryImportService의 재임포트가 AUTHORED family만 삭제 후 재생성할 수 있게 해준다 - anchor를
     * 통째로 delete-cascade하지 않는 대신, 이 스토리의 기존 anchor id들에 속한 AUTHORED family만
     * 직접 지운다. LIVE_GENERATED family는 이 목록에 anchorId가 있어도 절대 지워지지 않는다.
     */
    @Modifying
    @Query("delete from StoryActionFamily f where f.anchor.id in :anchorIds and f.origin = :origin")
    int deleteByAnchor_IdInAndOrigin(@Param("anchorIds") List<String> anchorIds, @Param("origin") FamilyOrigin origin);

    /** LiveBranchGenerationService.enqueue()의 앵커당 상한 체크. */
    long countByAnchor_IdAndOrigin(String anchorId, FamilyOrigin origin);

    /** StoryImportService가 재임포트 시 보존해야 할(=삭제 대상에서 빼야 할) 삽화 asset을 찾는 데 쓴다. */
    List<StoryActionFamily> findByAnchor_Story_IdAndOrigin(String storyId, FamilyOrigin origin);

    /** 리조인 후보 앵커 목록 - 이미 fallback이 임포트된 family들이 실제로 향하는 곳들. */
    @Query("select distinct f.rejoinTarget from StoryActionFamily f "
            + "where f.anchor.id = :anchorId and f.rejoinTarget is not null")
    List<String> findDistinctRejoinTargetsByAnchorId(@Param("anchorId") String anchorId);
}
