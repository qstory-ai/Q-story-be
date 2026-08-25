package com.qstory.backend.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * LLM에게 전달되는 라우팅 정책으로, 스토리가 자신의 route-context.yaml에서 지정하는 버전 라벨 아래에
 * 저장된다.
 *
 * <p>예전에는 이 라벨은 스토리와 함께 DB로 들어갔지만, 그 라벨이 가리키는 실제 텍스트는 OpenRouterClient
 * 안의 String.join이었다 - 그래서 정책을 수정하려면 재배포가 필요했고 그래도 버전은 절대 바뀌지 않았으며,
 * 반대로 버전을 올려도 정책은 절대 바뀌지 않았다. 이제는 둘을 함께 기록한다.
 *
 * <p>특정 스토리에 종속되지 않는다: 여러 스토리가 같은 버전을 지정할 수 있다.
 */
@Entity
@Table(name = "route_prompt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutePrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String version;

    /** 공백으로 이어붙인 줄들이 system 메시지를 이룬다. 버전 줄은 요청 시점에 맨 앞에 덧붙는다. */
    @Column(name = "system_text", nullable = false, columnDefinition = "text")
    private String systemText;

    /** 공백으로 이어붙인 줄들이 요청 페이로드의 "instruction" 필드를 이룬다. */
    @Column(name = "instruction_text", nullable = false, columnDefinition = "text")
    private String instructionText;

    /**
     * 라우팅 프롬프트의 안전 규칙 블록(danger/violence/off-story/forbidden-future-info ->
     * GENTLE_REDIRECT, 절대 safe로 재분류하지 않음)으로, 여기서 한 번만 작성하고 Java 코드 안에 다시
     * 옮겨 적는 대신 companion-chat 프롬프트(CompanionChatPipelineService 참고)에 그대로 공유된다.
     * 기존 행들에 대해 백필되기 전까지는 null일 수 있다.
     */
    @Column(name = "companion_safety_fragment", columnDefinition = "text")
    private String companionSafetyFragment;
}
