package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.StoryCastEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryCastEntityRepository extends JpaRepository<StoryCastEntity, UUID> {

    List<StoryCastEntity> findByStory_Id(String storyId);
}
