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
 * 스토리의 선형 내레이션 스크립트 안의 하나의 씬(scene), 예: "HG-F04". 실제 서사 콘텐츠
 * (대사, 비주얼, 질문/분기 지점)는 순서가 있는 {@link StorySegment} 행들에 들어 있다.
 */
@Entity
@Table(name = "story_scene")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryScene {

    /** 안정적인 콘텐츠 id (예: "HG-F04"). */
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    @Column(nullable = false)
    private String title;

    /** 스토리의 선형 스크립트 안에서의 위치 - 낮은 값이 먼저 재생된다. */
    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String checkpointId;
}
