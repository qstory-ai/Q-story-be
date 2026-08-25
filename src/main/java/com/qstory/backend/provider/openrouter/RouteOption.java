package com.qstory.backend.provider.openrouter;

/**
 * branchLine은 이 옵션이 선택된 후 분기 콘텐츠가 재생되는 동안 낭독되는 짧은 대사이다
 * (Tier 1 동적 내레이션 참고 - QuestionPipelineService는 이를 위해 더 이상 두 번째 LLM 호출이
 * 필요하지 않은데, label/meaning과 동일한 chat/completions 응답 안에서 함께 작성되기 때문이다).
 */
public record RouteOption(String id, String label, String meaning, String actionFamilyId, String branchLine) {

    public RouteOption withCopy(String label, String meaning) {
        return new RouteOption(id, label, meaning, actionFamilyId, branchLine);
    }
}
