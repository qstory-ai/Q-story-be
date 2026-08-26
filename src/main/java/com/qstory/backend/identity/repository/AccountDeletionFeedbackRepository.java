package com.qstory.backend.identity.repository;

import com.qstory.backend.identity.entity.AccountDeletionFeedback;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountDeletionFeedbackRepository extends JpaRepository<AccountDeletionFeedback, UUID> {}
