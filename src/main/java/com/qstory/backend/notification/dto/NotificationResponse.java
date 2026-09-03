package com.qstory.backend.notification.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * FE가 벨/드로어에서 표시할 알림 하나. body/href는 null 가능(선택). readAt이 null이면 unread.
 */
public record NotificationResponse(
        UUID id,
        String kind,
        String title,
        String body,
        String href,
        Instant readAt,
        Instant createdAt) {}
