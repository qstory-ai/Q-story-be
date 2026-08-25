package com.qstory.backend.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * {@link StoryScene} 안의, 저작 순서대로 배열된 하나의 서사적 비트(beat) - 즉
 * visual/utterance/interaction/anchor/rejoin/checkpoint/trace/sfx 블록. 프론트엔드 저작
 * 파이프라인의 GeneratedStorySegment 판별 유니온(discriminated union) (kind + kind별 payload)을
 * 그대로 반영한다.
 */
@Entity
@Table(name = "story_segment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorySegment {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scene_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private StoryScene scene;

    /** 씬의 세그먼트들 내에서의 위치 - 낮은 값이 먼저 재생된다. */
    @Column(nullable = false)
    private int displayOrder;

    /** "visual" | "utterance" | "interaction" | "anchor" | "rejoin" | "checkpoint" | "trace" | "sfx" 중 하나. */
    @Column(nullable = false)
    private String kind;

    /**
     * kind == "interaction"일 때만 true다 - 명시적인 branch-point 플래그로, 스토리가 멈추고
     * 아이에게 질문을 제시하는 지점을 나타낸다. 프론트엔드가 런타임에 questionAnchors를
     * audio-group의 인접성과 대조해가며 이를 다시 유추할 필요가 없게 해준다.
     */
    @Column(name = "is_branch_point", nullable = false)
    @Builder.Default
    private boolean branchPoint = false;

    /** kind별로 다른 필드들 (speaker/role/text, visualId, slot, target, entry/action/exit state, ...). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();

    /**
     * 이 utterance의 사전 렌더링된 내레이션이 실제로 뭐라고 말하는지를 나타내며, 오디오를 배포한
     * import에 의해 설정된다. 오래된 상태(staleness) 여부는 저장된 플래그가 아니라
     * {@code !text.equals(narrationText)}로 판단하므로, 줄을 수정했다가 다시 원래대로 되돌려도
     * 잘못 표시되는 것이 없다. 절대 내레이션되지 않는 kind에 대해서는 null이다.
     */
    @Column(name = "narration_text", columnDefinition = "text")
    private String narrationText;
}
