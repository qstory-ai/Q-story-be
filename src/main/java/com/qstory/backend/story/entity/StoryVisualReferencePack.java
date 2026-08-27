package com.qstory.backend.story.entity;

import com.qstory.backend.common.enums.VisualReferenceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 이미지 생성 프롬프트에 텍스트로 덧붙이는 캐릭터/장소/소품/스타일 불변 사실 - fe/q-story-web의
 * visual-generation-contract.ts에 있는 VisualReferencePack과 1:1 매핑이다. 참조 이미지 한 장에만
 * 의존하지 않고 텍스트 규칙으로도 이중 보강해 인물 외형 일관성을 지킨다(계획 문서 Phase 2 §4).
 *
 * <p>label은 캐릭터의 경우 StoryCast.speakerId에서 "&lt;STORY&gt;-SPK-" 접두어를 뗀 값과 일치해야
 * 한다(예: speakerId "HG-SPK-GRETEL" -&gt; label "GRETEL") - LiveBranchExecutionWorker.buildImagePrompt()가
 * imageBrief.characters(스토리별 speakerId 값)를 그렇게 정규화해 조회한다.
 */
@Entity
@Table(name = "story_visual_reference_pack")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryVisualReferencePack {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VisualReferenceKind kind;

    @Column(nullable = false)
    private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "immutable_facts", nullable = false, columnDefinition = "jsonb")
    private List<String> immutableFacts;
}
