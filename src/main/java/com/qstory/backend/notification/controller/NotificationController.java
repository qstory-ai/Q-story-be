package com.qstory.backend.notification.controller;

import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.notification.dto.NotificationListResponse;
import com.qstory.backend.notification.dto.NotificationResponse;
import com.qstory.backend.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인앱 알림 조회/읽음 처리. 발행은 각 도메인 서비스가 NotificationPublisher로 수행한다.
 * 역할 제한은 두지 않는다 - 어떤 role도 알림을 받을 수 있어야 하고, 어차피 사용자별로만 조회된다.
 */
@Tag(name = "Notifications", description = "In-app notification bell + drawer")
@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    private final NotificationService service;
    private final CurrentUserResolver currentUserResolver;

    public NotificationController(NotificationService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List notifications", description = "최신 30개 + 전체 unread 카운트.")
    @GetMapping
    public NotificationListResponse list() {
        return service.list(currentUserResolver.require());
    }

    @Operation(summary = "Mark a single notification as read")
    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable("id") UUID id) {
        return service.markRead(currentUserResolver.require(), id);
    }

    @Operation(summary = "Mark all notifications as read")
    @PostMapping("/read-all")
    public void markAllRead() {
        service.markAllRead(currentUserResolver.require());
    }

    @Operation(summary = "Delete a single notification",
            description = "본인이 소유한 알림만 삭제 가능. 알림은 ephemeral 데이터라 hard delete.")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") UUID id) {
        service.delete(currentUserResolver.require(), id);
    }
}
