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
 * One narrative beat within a {@link StoryScene}, in authoring order - a
 * visual/utterance/interaction/anchor/rejoin/checkpoint/trace/sfx block. Mirrors the frontend
 * authoring pipeline's GeneratedStorySegment discriminated union (kind + kind-specific payload).
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

    /** Position within the scene's segments - lower plays first. */
    @Column(nullable = false)
    private int displayOrder;

    /** "visual" | "utterance" | "interaction" | "anchor" | "rejoin" | "checkpoint" | "trace" | "sfx". */
    @Column(nullable = false)
    private String kind;

    /**
     * True only for kind == "interaction" - the explicit branch-point flag: the point where the
     * story pauses and offers the child a question, instead of the frontend having to re-derive
     * this by cross-referencing questionAnchors against audio-group adjacency at runtime.
     */
    @Column(name = "is_branch_point", nullable = false)
    @Builder.Default
    private boolean branchPoint = false;

    /** Kind-specific fields (speaker/role/text, visualId, slot, target, entry/action/exit state, ...). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();
}
