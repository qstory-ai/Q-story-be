package com.qstory.backend.story.entity;

import com.qstory.backend.choicecopy.ChoiceCopyVariant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/** 아이가 앵커에서 선택할 수 있는 하나의 액션, 예: "A_OBSERVE_BIRD". 전역적으로 고유한 id. */
@Entity
@Table(name = "story_action_family")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryActionFamily {

    /** 안정적인 콘텐츠 id (예: "A_OBSERVE_BIRD") - choice_copy_variant와 다른 family들의 requiresPriorFamilyIds에서 참조된다. */
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "anchor_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private StoryAnchor anchor;

    @Column(nullable = false, length = 300)
    private String meaning;

    @Column(nullable = false, length = 300)
    private String acknowledgementText;

    @Column(nullable = false, length = 300)
    private String reportSummary;

    @Column(nullable = false)
    private String bridgeAudioId;

    @Column(nullable = false)
    private String branchAssetId;

    /** 다른 action-family id들: 이 family는 아이가 이전에 이들 중 하나를 선택했을 때만 제공된다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> requiresPriorFamilyIds = List.of();

    @Column(nullable = false)
    private int displayOrder;

    /** 이 family의 THREE_PATHS 옵션에 대해 제공되는, 검토자가 작성한 3개의 label/meaning 변형(variant)으로, 표시 순서대로 정렬된다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<ChoiceCopyVariant> choiceCopyVariants = List.of();

    /**
     * branch-response 스크립트 자체의 필드들 (그 세그먼트들은 StoryFallbackSegment 참고) -
     * 이 family의 id와 동일한 키 공간을 공유하며(1:1), fallback 콘텐츠는 위의 시더(seeder)가
     * 작성한 이 행 자체의 필드들과는 별도로 배포되기 때문에 POST /v1/admin/stories/import를 통해
     * 임포트되기 전까지는(StoryImportService 참고) null이다.
     */
    private String requiresFamilyId;

    private String rejoinSlot;

    private String rejoinTarget;
}
