package com.qstory.backend.story.entity;

import com.qstory.backend.common.enums.RevisionOperation;
import com.qstory.backend.common.enums.RevisionTarget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One appended authoring edit.
 *
 * <p>Story content is moving out of the git-tracked content files and into this database, which
 * would otherwise drop the three things git was quietly providing: who changed it, what it looked
 * like before, and how to get back. Every authoring write appends a row here carrying the target's
 * full prior state, so history is readable and a revert is replaying an older snapshot.
 */
@Entity
@Table(name = "story_revision")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "story_id", nullable = false)
    private String storyId;

    /** Monotonic per story; also the optimistic-concurrency token an editor sends back. */
    @Column(nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private RevisionTarget targetType;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RevisionOperation operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    /** Null for the content:import pipeline, which acts as the system rather than a person. */
    @Column(name = "author_id")
    private UUID authorId;

    @Column(length = 500)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
