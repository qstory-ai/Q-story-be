package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.Story;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryRepository extends JpaRepository<Story, String> {}
