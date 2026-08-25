package com.qstory.backend.completionsurvey.repository;

import com.qstory.backend.completionsurvey.entity.CompletionSurvey;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompletionSurveyRepository extends JpaRepository<CompletionSurvey, UUID> {}
