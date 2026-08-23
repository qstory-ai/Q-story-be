package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryCast;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryCastRepository extends JpaRepository<StoryCast, UUID> {

    List<StoryCast> findByStory_Id(String storyId);
}
