package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.RoutePrompt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutePromptRepository extends JpaRepository<RoutePrompt, Long> {

    Optional<RoutePrompt> findByVersion(String version);
}
