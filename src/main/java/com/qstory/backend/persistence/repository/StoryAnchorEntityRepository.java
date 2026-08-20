package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.StoryAnchorEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryAnchorEntityRepository extends JpaRepository<StoryAnchorEntity, String> {

    List<StoryAnchorEntity> findByStory_Id(String storyId);
}
