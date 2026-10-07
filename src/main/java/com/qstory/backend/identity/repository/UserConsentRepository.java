package com.qstory.backend.identity.repository;

import com.qstory.backend.identity.entity.UserConsent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {}
