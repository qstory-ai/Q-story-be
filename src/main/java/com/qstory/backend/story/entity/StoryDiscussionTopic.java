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

/**
 * 독서 토론 주제 뱅크 항목 (스토리 제작 규약 §5.1) - 완주 후 "책의 주제로 AI와 대화하기" 기능이
 * 읽어갈 재료. 안전 각색(§2.1)에서 드러난 가치 충돌과 부모 리포트의 "이어갈 대화 질문"에서 그대로
 * 파생된다.
 */
@Entity
@Table(name = "story_discussion_topic")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryDiscussionTopic {

    /** 안정적인 콘텐츠 id (예: "HG-DISC-01"). */
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    @Column(nullable = false, length = 500)
    private String statement;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> relatedSceneIds = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> relatedAssetIds = List.of();

    @Column(nullable = false, length = 300)
    private String ageHint;

    @Column(nullable = false)
    private boolean safetyNoForcedAnswer;

    @Column(nullable = false)
    private boolean safetyRespectChildOpinion;
}
