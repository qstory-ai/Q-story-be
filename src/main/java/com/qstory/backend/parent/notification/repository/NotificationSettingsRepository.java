package com.qstory.backend.parent.notification.repository;

import com.qstory.backend.parent.notification.entity.NotificationSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {}
