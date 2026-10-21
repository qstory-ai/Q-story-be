package com.qstory.backend.realtimeimage.entity;

import com.qstory.backend.common.enums.RealtimeImageKind;
import com.qstory.backend.common.enums.RealtimeImageStatus;
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
 * 실시간 삽화 한 장의 기록(db/schema/089). 상한을 세는 단위이자 프런트가 폴링하는 작업 id다.
 * 자리를 먼저 잡고(QUEUED) 만들기 때문에, 동시에 여러 요청이 와도 질문 지점·세션당 상한을 넘기지 않는다.
 */
@Entity
@Table(name = "realtime_image")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RealtimeImage {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "story_id", nullable = false)
    private String storyId;

    @Column(name = "anchor_id", nullable = false)
    private String anchorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RealtimeImageKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RealtimeImageStatus status;

    @Column(name = "provider_request_id")
    private String providerRequestId;

    private String bucket;

    @Column(name = "object_name")
    private String objectName;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "prompt_hash")
    private String promptHash;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
