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
 * Same shape as {@link StorySegment}, scoped to the {@link StoryActionFamily} whose
 * branch-response script (its {@code requiresFamilyId}/{@code rejoinSlot}/{@code rejoinTarget}
 * fields) these segments belong to, instead of a scene.
 */
@Entity
@Table(name = "story_fallback_segment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryFallbackSegment {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private StoryActionFamily family;

    /** Position within the fallback's segments - lower plays first. */
    @Column(nullable = false)
    private int displayOrder;

    /** "visual" | "utterance" | "trace" | ... - see StorySegment.kind. */
    @Column(nullable = false)
    private String kind;

    /** Kept for symmetry with StorySegment; always false for fallback segments in current content. */
    @Column(name = "is_branch_point", nullable = false)
    @Builder.Default
    private boolean branchPoint = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();
}
