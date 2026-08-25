package com.qstory.backend.launchnotification.repository;

import com.qstory.backend.launchnotification.entity.LaunchNotificationRequest;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LaunchNotificationRequestRepository extends JpaRepository<LaunchNotificationRequest, UUID> {}
