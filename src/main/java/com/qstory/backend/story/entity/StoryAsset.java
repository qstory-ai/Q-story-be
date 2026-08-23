package com.qstory.backend.story.entity;

import com.qstory.backend.common.enums.AssetCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One illustration or narration clip belonging to a story.
 *
 * <p>The surrogate key is a plain identity long: this is the one story table whose rows are joined
 * and referenced by volume (256 for a single story), and nothing outside the DB needs to guess it.
 * What content authors write is {@link #slug}, which stays human-readable so a story script can say
 * {@code asset=home-table} instead of a number nobody can review in a diff.
 */
@Entity
@Table(
        name = "story_asset",
        uniqueConstraints = @UniqueConstraint(columnNames = {"story_id", "slug"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    /** Authoring-time name, unique within a story, e.g. "home-table" or "a-observe-bird-01". */
    @Column(nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AssetCategory category;

    /** Path under the story's asset root, e.g. "illustrations/hg-art-01-home-table.jpg". */
    @Column(nullable = false)
    private String file;

    /** Subresource-integrity hash of the file, recomputed by `npm run content:fix`. */
    @Column(nullable = false)
    private String integrity;

    /** Set for BRANCH_ART and BRIDGE only - the action family the asset belongs to. */
    @Column(name = "family_id")
    private String familyId;

    /** 1-based panel index within a family's branch art; null outside BRANCH_ART. */
    private Integer panel;

    /** When this file was re-rendered at runtime; null for a file shipped with the build. */
    @Column(name = "rendered_at")
    private java.time.Instant renderedAt;

    /** The cast voice the re-render used, so a later cast change is detectable by comparison. */
    @Column(name = "rendered_voice", length = 64)
    private String renderedVoice;
}
