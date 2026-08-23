package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryScene;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorySceneRepository extends JpaRepository<StoryScene, String> {

    List<StoryScene> findByStory_IdOrderBySequenceAsc(String storyId);
}
