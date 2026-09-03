package com.qstory.backend.notification.dto;

import java.util.List;

/**
 * `GET /v1/notifications` 응답. unreadCount는 목록에 담긴 것뿐 아니라 사용자 전체 unread를
 * 세어 반환하므로, 페이지 상단 벨의 뱃지가 목록 페이지네이션과 무관하게 정확한 수를 보여줄
 * 수 있다.
 */
public record NotificationListResponse(List<NotificationResponse> notifications, long unreadCount) {}
