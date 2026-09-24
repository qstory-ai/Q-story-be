package com.qstory.backend.companionchat.entity;

import com.qstory.backend.common.enums.CompanionInteractionMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * companion-chat 한 턴에서 파생된 신호만 저장 - transcript/responseText 컬럼은 여기엔 없다.
 * 이 테이블은 부모 리포트 스냅샷용 태그 집계(90일 보존)만 책임진다. 원문(아이의 말·캐릭터의 답)은
 * 2026-09부터 별도의 열람 API 없는 원장 conversation_record(db/schema/052, 보존 기간 별도 설정)에
 * 남는다 - 예전 "원본 음성/전사문 영구 저장 금지" 원칙(core-contracts.design.md §CC-NFR-03)은
 * 음성 원본에 대해서만 유지된다.
 */
@Entity
@Table(
        name = "companion_chat_turn",
        indexes = {
            @Index(name = "companion_chat_turn_conversation_idx", columnList = "conversation_id, occurred_at"),
            @Index(name = "companion_chat_turn_occurred_idx", columnList = "occurred_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanionChatTurn {

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "story_id", nullable = false)
    private String storyId;

    @Column(name = "scene_id", nullable = false)
    private String sceneId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_mode", nullable = false)
    private CompanionInteractionMode interactionMode;

    /** CompanionTopicTag.label()에서 온 한글 라벨 - 모델이 확신을 갖고 태깅하지 못했다면 null. */
    @Column(name = "topic_tag")
    private String topicTag;

    /** CompanionToneTag.label()에서 온 한글 라벨 - 모델이 확신을 갖고 태깅하지 못했다면 null. */
    @Column(name = "tone_tag")
    private String toneTag;

    /** CompanionValueTag.label()에서 온 한글 라벨 - 모델이 확신을 갖고 태깅하지 못했다면 null. */
    @Column(name = "value_tag")
    private String valueTag;
}
