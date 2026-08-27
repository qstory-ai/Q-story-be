package com.qstory.backend.story.entity;

import com.qstory.backend.common.enums.RoutePromptStageKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * RoutePrompt(단일 시스템/instruction blob)를 대체하지 않고 그 옆에 추가된, Phase 2의 3단계
 * 파이프라인(SAFETY -> CLASSIFIER -> GENERATOR) 전용 프롬프트 테이블. RoutePrompt와 마찬가지로
 * "route_prompt_version" 라벨 아래 저장되며(스토리와 직접 연결되지 않음, 여러 스토리가 같은 버전을
 * 공유 가능), 그 버전 아래 세 stage 행이 하나씩 존재한다.
 *
 * <p>examples는 few-shot 예시 목록으로, 각 원소는 {"input": {...}, "output": {...}} 모양의 맵이다 -
 * OpenRouterClient.generateStructuredCompletion()이 이 쌍들을 실제 user 메시지 앞에 user/assistant
 * 메시지로 번갈아 삽입한다(RoutePromptService.StagePrompt/FewShotExample 참고).
 */
@Entity
@Table(name = "route_prompt_stage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutePromptStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_prompt_version", nullable = false)
    private String routePromptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoutePromptStageKind stage;

    /** 공백으로 이어붙인 줄들이 system 메시지를 이룬다 - RoutePrompt.systemText와 같은 관례. */
    @Column(name = "system_text", nullable = false, columnDefinition = "text")
    private String systemText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "examples_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> examples = List.of();
}
