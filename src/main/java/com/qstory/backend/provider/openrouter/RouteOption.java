package com.qstory.backend.provider.openrouter;

/**
 * branchLine은 이 옵션이 선택된 후 분기 콘텐츠가 재생되는 동안 낭독되는 짧은 대사이다 -
 * label/meaning과 같은 content_generator 응답 안에서 함께 작성되므로 별도 LLM 호출이 필요 없다.
 */
public record RouteOption(String id, String label, String meaning, String actionFamilyId, String branchLine) {

    public RouteOption withCopy(String label, String meaning) {
        return new RouteOption(id, label, meaning, actionFamilyId, branchLine);
    }
}
