package com.qstory.backend.conversationrecord.entity;

import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.common.enums.ConversationRecordKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 아이의 발화 원문과 캐릭터의 답을 그대로 남기는 append-only 원장 - db/schema/052 참고.
 * 의도적으로 Setter가 없다: 한 번 쓰면 고치지 않고, 읽는 API도 없다. 보존 만료 삭제만
 * ConversationRecordRetentionScheduler가 한다.
 *
 * <p>companion_chat_turn(태그만, 90일)과는 별개 테이블이다 - 그쪽의 "원문은 남기지 않는다"는
 * 전제와 부모 리포트 스냅샷 집계를 건드리지 않기 위해서다.
 */
@Entity
@Table(
        name = "conversation_record",
        indexes = {
            @Index(name = "conversation_record_session_idx", columnList = "session_id, recorded_at"),
            @Index(name = "conversation_record_recorded_idx", columnList = "recorded_at"),
            @Index(name = "conversation_record_child_idx", columnList = "child_id, recorded_at"),
            @Index(name = "conversation_record_tutor_student_idx", columnList = "tutor_student_id, recorded_at")
        })
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationRecord {

    @Id
    private UUID id;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConversationRecordKind kind;

    /** 프론트가 이야기 세션당 하나 만드는 conversationId. 익명/구버전 클라이언트는 null. */
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "story_id", nullable = false)
    private String storyId;

    @Column(name = "scene_id", nullable = false)
    private String sceneId;

    @Column(name = "anchor_id")
    private String anchorId;

    @Column(name = "question_round")
    private Integer questionRound;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_mode", nullable = false, length = 16)
    private ConversationInputMode inputMode;

    @Column(length = 16)
    private String locale;

    @Column(name = "source_mime_type", length = 64)
    private String sourceMimeType;

    /** 아이가 말하거나 쓴 원문 - STT 결과 그대로, 응답 생성에 쓰인 것과 같은 문자열. */
    @Column(name = "child_text", nullable = false, columnDefinition = "text")
    private String childText;

    /** 캐릭터의 답. *_TRANSCRIPT 행은 아직 답이 없어 null. */
    @Column(name = "response_text", columnDefinition = "text")
    private String responseText;

    @Column(name = "speaker_id")
    private String speakerId;

    /** 질문: route(DIRECT_ACTION 등). 상시대화: interactionMode(ANSWER/GENTLE_REDIRECT). */
    @Column(length = 64)
    private String route;

    @Column(name = "action_family_id")
    private String actionFamilyId;

    @Column(name = "coverage_status", length = 32)
    private String coverageStatus;

    @Column(name = "child_relevant_meaning", length = 500)
    private String childRelevantMeaning;

    /** 질문 THREE_PATHS 선택지 등 구조화 결과. 없으면 null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> options;

    @Column(name = "topic_tag")
    private String topicTag;

    @Column(name = "tone_tag")
    private String toneTag;

    @Column(name = "value_tag")
    private String valueTag;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "user_role", length = 32)
    private String userRole;

    @Column(name = "child_id")
    private UUID childId;

    @Column(name = "tutor_student_id")
    private UUID tutorStudentId;

    @Column(name = "lesson_id")
    private UUID lessonId;

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "prompt_version")
    private String promptVersion;
}
