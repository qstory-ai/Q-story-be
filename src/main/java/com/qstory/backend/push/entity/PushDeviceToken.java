package com.qstory.backend.push.entity;

import com.qstory.backend.push.PushPlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 앱 푸시(FCM) 기기 토큰 - db/schema/075-push-device-token.sql. 쓰기는 PushDeviceTokenRepository의 네이티브
 * 쿼리(upsert·비활성화)로 하고, 이 매핑은 ddl-auto=validate가 스키마와 어긋남을 잡게 하려고 둔다.
 */
@Entity
@Table(name = "push_device_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushDeviceToken {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token", nullable = false, length = 512, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private PushPlatform platform;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** FCM이 더는 쓸 수 없는 토큰이라고 답한 시각. null이면 보낼 대상이다. */
    @Column(name = "disabled_at")
    private Instant disabledAt;
}
