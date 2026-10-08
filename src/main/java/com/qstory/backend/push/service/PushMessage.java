package com.qstory.backend.push.service;

import java.util.UUID;

/** 인앱 알림 한 건을 푸시로 보낼 때의 내용. 필드는 Notification과 같다(body·href는 비어 있을 수 있다). */
public record PushMessage(UUID notificationId, String kind, String title, String body, String href) {}
