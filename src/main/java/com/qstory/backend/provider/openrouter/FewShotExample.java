package com.qstory.backend.provider.openrouter;

/**
 * route_prompt_stage.examples_json의 원소 하나 - 실제 user 메시지 앞에 user/assistant 메시지 쌍으로
 * 삽입되는 few-shot 예시다(OpenRouterClient.generateStructuredCompletion() 참고). 이 코드베이스에
 * 멀티턴(system 이후 user/assistant가 번갈아 오는) 프롬프팅 선례가 이전에 없었다 - Phase 2의 3단계
 * 파이프라인을 위해 새로 도입됐다.
 *
 * <p>input/output은 (version, stage) 조합당 불변이므로 RoutePromptService가 캐싱하는 시점에 이미
 * JSON 문자열로 직렬화해 둔다 - 같은 예시를 매 질문마다 다시 직렬화하지 않기 위함이다.
 */
public record FewShotExample(String input, String output) {}
