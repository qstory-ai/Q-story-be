package com.qstory.backend.notification.repository;

import com.qstory.backend.notification.entity.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUser_IdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<Notification> findByIdAndUser_Id(UUID id, UUID userId);

    long countByUser_IdAndReadAtIsNull(UUID userId);

    Optional<Notification> findByUser_IdAndDedupKey(UUID userId, String dedupKey);

    @Modifying
    @Query("update Notification n set n.readAt = :readAt where n.user.id = :userId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("readAt") Instant readAt);
}
