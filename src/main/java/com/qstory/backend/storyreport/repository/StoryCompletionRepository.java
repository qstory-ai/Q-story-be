package com.qstory.backend.storyreport.repository;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryCompletionRepository extends JpaRepository<StoryCompletion, UUID> {

    List<StoryCompletion> findByUser_IdOrderByCompletedAtDesc(UUID userId);

    Optional<StoryCompletion> findByIdAndUser_Id(UUID id, UUID userId);
}
