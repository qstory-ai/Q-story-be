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
 * {@link StorySegment}와 동일한 형태이지만, 씬(scene)이 아니라 이 세그먼트들이 속한 branch-response
 * 스크립트({@code requiresFamilyId}/{@code rejoinSlot}/{@code rejoinTarget} 필드)를 가진
 * {@link StoryActionFamily}를 기준으로 범위가 정해진다.
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

    /** fallback 세그먼트들 내에서의 위치 - 낮은 값이 먼저 재생된다. */
    @Column(nullable = false)
    private int displayOrder;

    /** "visual" | "utterance" | "trace" | ... - StorySegment.kind 참고. */
    @Column(nullable = false)
    private String kind;

    /** StorySegment와의 대칭성을 위해 유지된다; 현재 콘텐츠에서 fallback 세그먼트는 항상 false다. */
    @Column(name = "is_branch_point", nullable = false)
    @Builder.Default
    private boolean branchPoint = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();
}
