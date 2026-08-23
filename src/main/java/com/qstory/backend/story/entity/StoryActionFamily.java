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

/** One selectable action a child can take at an anchor, e.g. "A_OBSERVE_BIRD". Globally unique id. */
@Entity
@Table(name = "story_action_family")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryActionFamily {

    /** Stable content id (e.g. "A_OBSERVE_BIRD") - referenced by choice_copy_variant and by other families' requiresPriorFamilyIds. */
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

    /** Other action-family ids: this family is only offered if the child previously chose one of these. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> requiresPriorFamilyIds = List.of();

    @Column(nullable = false)
    private int displayOrder;

    /** The 3 reviewer-authored label/meaning variants offered for this family's THREE_PATHS option, in display order. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<ChoiceCopyVariant> choiceCopyVariants = List.of();

    /**
     * The branch-response script's own fields (see StoryFallbackSegment for its segments) -
     * same key space as this family's id (1:1), null until imported via POST
     * /v1/admin/stories/import (see StoryImportService), since fallback content ships separately
     * from this row's own seeder-authored fields above.
     */
    private String requiresFamilyId;

    private String rejoinSlot;

    private String rejoinTarget;
}
