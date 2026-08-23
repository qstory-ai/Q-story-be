package com.qstory.backend.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One scene in a story's linear narration script, e.g. "HG-F04". Its actual narrative content
 * (dialogue, visuals, the question/branch point) lives in ordered {@link StorySegment} rows.
 */
@Entity
@Table(name = "story_scene")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryScene {

    /** Stable content id (e.g. "HG-F04"). */
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    @Column(nullable = false)
    private String title;

    /** Position in the story's linear script - lower plays first. */
    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String checkpointId;
}
