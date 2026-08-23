package com.qstory.backend.story.entity;

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

/** One question point in a story, e.g. "HG-Q-A". Its actionFamilies live in StoryActionFamily. */
@Entity
@Table(name = "story_anchor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryAnchor {

    /** Stable content id (e.g. "HG-Q-A"). */
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    @Column(nullable = false)
    private String slot;

    @Column(nullable = false)
    private String sceneId;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(nullable = false)
    private String primarySpeakerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> allowedSpeakerIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> sttKeywords;

    @Column(nullable = false)
    private String defaultFallbackFamilyId;

    @Column(nullable = false)
    private String defaultRejoinAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> forbiddenKnowledge;

    /** Null when this anchor has no concern-choice fallback. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> concernChoiceFamilyIds;

    @Column(length = 300)
    private String concernChoiceResponseText;
}
