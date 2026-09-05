package com.qstory.backend.parent.notification.controller;

import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.parent.notification.dto.NotificationSettingsResponse;
import com.qstory.backend.parent.notification.dto.UpdateNotificationSettingsRequest;
import com.qstory.backend.parent.notification.service.NotificationSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notification settings", description = "Per-user notification preferences")
@RestController
@RequestMapping({"/v1/me/notification-settings", "/v1/parents/me/notification-settings"})
public class ParentNotificationSettingsController {

    private final NotificationSettingsService service;
    private final CurrentUserResolver currentUserResolver;

    public ParentNotificationSettingsController(
            NotificationSettingsService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Read notification settings",
            description = "Any signed-in user. Returns server defaults if no row exists yet.")
    @GetMapping
    public NotificationSettingsResponse read() {
        return service.read(currentUserResolver.require());
    }

    @Operation(summary = "Update notification settings",
            description = "Any signed-in user. Upserts on first call. Fields left null are unchanged.")
    @PatchMapping
    public NotificationSettingsResponse update(@RequestBody UpdateNotificationSettingsRequest request) {
        return service.update(currentUserResolver.require(), request);
    }
}
