package com.qstory.backend.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/**
 * 삽화 한 장의 생성 이력 (스토리 제작 규약 대원칙 "재현 가능성") - 프롬프트를 잃으면 새 장면·새
 * 기능이 붙을 때마다 그 그림을 다시 만들 수 없다는 게 이 표가 존재하는 이유다.
 *
 * <p>{@code status}가 RECOVERED가 아니면(LOST/UNKNOWN) prompt/model/inputHash/generatedAt은
 * null이고 대신 {@code note}에 왜 없는지를 적어 둔다 - 없는 값을 지어내 채우지 않는다.
 */
@Entity
@Table(name = "story_visual_provenance", uniqueConstraints = @UniqueConstraint(columnNames = {"story_id", "asset_slug"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryVisualProvenance {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    /** StoryAsset.slug와 같은 값 - 조인이 아니라 평범한 문자열 컬럼(StoryAsset.familyId와 같은 방식). */
    @Column(name = "asset_slug", nullable = false)
    private String assetSlug;

    /** RECOVERED | LOST | UNKNOWN - 값 종류가 고정적이지 않을 수 있어 CastRole처럼 자유 문자열로 둔다. */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(columnDefinition = "text")
    private String prompt;

    private String model;

    private String inputHash;

    private Instant generatedAt;

    private String approvedBy;

    /** status가 RECOVERED가 아닐 때 왜 기록이 없는지 설명. */
    @Column(length = 500)
    private String note;
}
