package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryAnchor;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryAnchorRepository extends JpaRepository<StoryAnchor, String> {

    List<StoryAnchor> findByStory_Id(String storyId);
}
