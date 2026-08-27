package com.qstory.backend.story.repository;

import com.qstory.backend.common.enums.RoutePromptStageKind;
import com.qstory.backend.story.entity.RoutePromptStage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutePromptStageRepository extends JpaRepository<RoutePromptStage, Long> {

    Optional<RoutePromptStage> findByRoutePromptVersionAndStage(String routePromptVersion, RoutePromptStageKind stage);
}
