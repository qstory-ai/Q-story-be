package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryPersona;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryPersonaRepository extends JpaRepository<StoryPersona, UUID> {

    List<StoryPersona> findByStory_Id(String storyId);

    Optional<StoryPersona> findByStory_IdAndCastTag(String storyId, String castTag);
}
