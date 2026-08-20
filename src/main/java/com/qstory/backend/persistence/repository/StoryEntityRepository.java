package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.StoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryEntityRepository extends JpaRepository<StoryEntity, String> {}
