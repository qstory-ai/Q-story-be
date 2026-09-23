package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryPersona;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryPersonaRepository extends JpaRepository<StoryPersona, UUID> {

    List<StoryPersona> findByStory_Id(String storyId);

    Optional<StoryPersona> findByStory_IdAndCastTag(String storyId, String castTag);

    /** CompanionPersonaRegistry.reload()용 - 트랜잭션 밖에서 story.id를 읽어야 하므로 story를 함께 가져온다. */
    @Query("select p from StoryPersona p join fetch p.story")
    List<StoryPersona> findAllWithStory();
}
