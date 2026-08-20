package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.StoryActionFamilyEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryActionFamilyEntityRepository extends JpaRepository<StoryActionFamilyEntity, String> {

    List<StoryActionFamilyEntity> findByAnchor_IdOrderByDisplayOrderAsc(String anchorId);
}
