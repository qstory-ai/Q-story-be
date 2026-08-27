package com.qstory.backend.provider.openrouter;

/**
 * 1단계 safety_scope_gate의 검증된 출력. verdict가 REDIRECT일 때만 redirectReason/responseText가
 * 채워진다 - PASS면 둘 다 null이다(RouteResultValidator.validateSafetyVerdict 참고).
 */
public record SafetyVerdict(String verdict, String redirectReason, String responseText, String modelId) {

    public boolean isRedirect() {
        return "REDIRECT".equals(verdict);
    }
}
