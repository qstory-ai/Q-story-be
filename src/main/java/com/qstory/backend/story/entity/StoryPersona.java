package com.qstory.backend.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 캐릭터 페르소나 시트 (스토리 제작 규약 §2.3) - cast.yaml(목소리)과 캐릭터북(외형)이 여기서
 * 파생되는 원천. {@code castTag}로 StoryCast와 1:1 연결된다(story_id, cast_tag 유니크).
 *
 * <p>{@code knowledgeBoundary*} 세 필드는 규약이 "추후 채움 불허 필수 항목"으로 못박은 부분 -
 * 상시 질문(§4)과 인물과 대화(§5.2) 기능이 스포일러를 막는 유일한 방어선이라, personas.yaml
 * import 시점부터 항상 채워져 있어야 한다(StoryImportService가 빈 리스트라도 필수로 채운다).
 */
@Entity
@Table(name = "story_persona", uniqueConstraints = @UniqueConstraint(columnNames = {"story_id", "cast_tag"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryPersona {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    /** cast.yaml의 CAST_TAG와 동일한 키 (예: "GRETEL"). */
    @Column(name = "cast_tag", nullable = false)
    private String castTag;

    /** StoryCast.speakerId의 복제값 - 조인 없이 바로 검증/조회할 수 있게 나란히 둔다. */
    @Column(nullable = false)
    private String speakerId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String ageBand;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> personalityTraits;

    @Column(nullable = false, length = 300)
    private String personalityOneLiner;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> speechEndings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> speechCatchphrases = List.of();

    /** 자유 문자열(예: SHORT/MEDIUM/LONG) - CastRole처럼 분기 로직이 없어 enum으로 두지 않는다. */
    @Column(nullable = false)
    private String sentenceLengthBias;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> emotionRangeAllowed;

    @Column(nullable = false, length = 300)
    private String emotionCapNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> voiceTexture;

    /** 내레이터처럼 화면에 등장하지 않는 페르소나는 빈 리스트일 수 있다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> appearanceFacts = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<PersonaRelationship> relationships = List.of();

    /** ⭐ 상시 질문/인물과 대화 기능의 스포일러 방지 근거 - "아는 것". */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> knowledgeKnows = List.of();

    /** ⭐ "모르는 것". */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> knowledgeDoesNotKnow = List.of();

    /** ⭐ "절대 먼저 말하지 않는 것"(스포일러 목록). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> knowledgeNeverRevealsFirst = List.of();

    /** 변장(동일인) 연결 키 - StoryCast.samePersonKey와 반드시 일치해야 한다. */
    private String samePersonKey;
}
