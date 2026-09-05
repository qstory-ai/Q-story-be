package com.qstory.backend.parent.notification.dto;

/** 부분 업데이트 - null인 필드는 그대로 두고 값이 있는 필드만 반영한다. */
public record UpdateNotificationSettingsRequest(
        Boolean marketingEnabled, Boolean lessonReminderEnabled, Boolean lessonReportEnabled) {}
