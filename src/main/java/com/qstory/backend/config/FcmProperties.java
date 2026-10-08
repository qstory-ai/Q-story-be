package com.qstory.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 앱 푸시(FCM HTTP v1) 설정. serviceAccountJson은 Firebase 서비스 계정 키 JSON 원문 또는 그 base64다
 * (QSTORY_FCM_SERVICE_ACCOUNT_JSON). projectId는 비우면 JSON의 project_id를 쓴다(QSTORY_FCM_PROJECT_ID).
 * 키가 비어 있으면 푸시는 아무것도 하지 않고, 인앱 알림은 그대로 쌓인다.
 */
@ConfigurationProperties(prefix = "qstory.fcm")
public record FcmProperties(String serviceAccountJson, String projectId) {}
