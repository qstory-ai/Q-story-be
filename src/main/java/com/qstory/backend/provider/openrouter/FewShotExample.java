package com.qstory.backend.provider.openrouter;

/**
 * 구조화 응답 호출의 few-shot 예시 하나 - 실제 user 메시지 앞에 user/assistant 메시지 쌍으로
 * 삽입된다(OpenRouterClient.generateStructuredCompletion() 참고). input/output은 이미 직렬화된 문자열이다.
 */
public record FewShotExample(String input, String output) {}
