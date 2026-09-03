package com.qstory.backend.notification.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.dto.NotificationListResponse;
import com.qstory.backend.notification.dto.NotificationResponse;
import com.qstory.backend.notification.entity.Notification;
import com.qstory.backend.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 목록 조회와 읽음 처리. 발행은 NotificationPublisher가 담당(도메인 서비스에서 호출).
 * 조회는 사용자의 최신 N개(기본 30)만 반환한다 - IA 시나리오는 며칠~한 주 이내의 이벤트만
 * 훑는 벨/드로어라 페이지네이션은 아직 필요치 않다.
 */
@Service
public class NotificationService {

    /** 벨 드로어가 한 번에 보여줄 최대 항목 수. */
    private static final int DEFAULT_LIMIT = 30;

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public NotificationListResponse list(CurrentUser caller) {
        List<NotificationResponse> notifications = repository
                .findByUser_IdOrderByCreatedAtDesc(caller.userId(), PageRequest.of(0, DEFAULT_LIMIT))
                .stream()
                .map(NotificationService::toDto)
                .toList();
        long unreadCount = repository.countByUser_IdAndReadAtIsNull(caller.userId());
        return new NotificationListResponse(notifications, unreadCount);
    }

    @Transactional
    public NotificationResponse markRead(CurrentUser caller, UUID notificationId) {
        Notification notification = repository.findByIdAndUser_Id(notificationId, caller.userId())
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.NOT_FOUND, "알림을 찾지 못했어요.", 404));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
        }
        return toDto(notification);
    }

    @Transactional
    public void markAllRead(CurrentUser caller) {
        repository.markAllRead(caller.userId(), Instant.now());
    }

    private static NotificationResponse toDto(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getKind(),
                n.getTitle(),
                n.getBody(),
                n.getHref(),
                n.getReadAt(),
                n.getCreatedAt());
    }
}
