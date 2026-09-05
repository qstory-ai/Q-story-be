package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryVisualProvenance;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryVisualProvenanceRepository extends JpaRepository<StoryVisualProvenance, UUID> {

    List<StoryVisualProvenance> findByStory_Id(String storyId);
}
