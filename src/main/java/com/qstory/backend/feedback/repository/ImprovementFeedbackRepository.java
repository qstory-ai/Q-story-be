package com.qstory.backend.feedback.repository;

import com.qstory.backend.feedback.entity.ImprovementFeedback;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImprovementFeedbackRepository extends JpaRepository<ImprovementFeedback, UUID> {}
