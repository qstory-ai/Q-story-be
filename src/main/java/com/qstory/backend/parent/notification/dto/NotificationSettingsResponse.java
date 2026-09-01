package com.qstory.backend.parent.notification.dto;

import com.qstory.backend.parent.notification.entity.NotificationSettings;

public record NotificationSettingsResponse(boolean marketingEnabled) {

    public static NotificationSettingsResponse of(NotificationSettings settings) {
        return new NotificationSettingsResponse(settings.isMarketingEnabled());
    }

    /** 아직 저장된 행이 없는 사용자에게 반환할 서버 기본값 - 마이그레이션의 DEFAULT와 일치시킨다. */
    public static NotificationSettingsResponse defaults() {
        return new NotificationSettingsResponse(true);
    }
}
