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
 * companion-chat 한 턴에서 파생된 신호만 저장 - transcript/responseText 컬럼은 의도적으로 없다.
 * 아이의 원문 발화는 응답 생성에 사용된 후 폐기되며, 이는 이 제품 전반의 "원본 음성/전사문
 * 영구 저장 금지" 원칙(core-contracts.design.md §CC-NFR-03 참고)과 동일하다; 구조화된 태그만
 * 요청 처리가 끝난 뒤에도 남는다.
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
