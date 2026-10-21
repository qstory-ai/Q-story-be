package com.qstory.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 실시간 삽화(Higgsfield GPT Image 2.5 Flare) 설정 - 새 선택지 그림(LiveBranchExecutionWorker)과 시도 장면 막힘 그림
 * (CompanionChatPipelineService)을 아이가 말한 내용대로 만든다. 미리 만드는 정적 삽화와 같은 모델·같은 공통 프롬프트
 * (스타일 01)·같은 인물 시트를 써서 그림체가 섞이지 않게 한다.
 *
 * <p>키가 비어 있으면(configured()=false) 새 선택지는 예전처럼 OpenRouter 이미지 모델로, 시도 장면은 미리 만든 막힘 그림으로 간다.
 *
 * @param maxPerAnchorSession 질문 지점 하나·읽기 세션 하나에서 만들 수 있는 실시간 그림 수(새 선택지 + 시도 장면 합계).
 *                            장당 2k·high ≈ $0.275라 기본 3장 - 한 질문 지점에서 아무리 많이 말해도 1달러를 넘지 않게.
 * @param pollTimeoutMs       제출 뒤 완성을 기다리는 상한. 2k는 보통 20~40초, 4k는 50초 안팎.
 */
@ConfigurationProperties(prefix = "qstory.realtime-image")
public record RealtimeImageProperties(
        Boolean enabled, String apiKeyId, String apiKeySecret, String resolution, String quality,
        Integer maxPerAnchorSession, Long pollTimeoutMs) {

    @ConstructorBinding
    public RealtimeImageProperties {
        enabled = enabled == null || enabled;
        resolution = resolution == null || resolution.isBlank() ? "2k" : resolution.trim();
        quality = quality == null || quality.isBlank() ? "high" : quality.trim();
        maxPerAnchorSession = maxPerAnchorSession == null || maxPerAnchorSession < 0 ? 3 : Math.min(maxPerAnchorSession, 20);
        pollTimeoutMs = pollTimeoutMs == null || pollTimeoutMs < 10_000 ? 120_000L : pollTimeoutMs;
    }

    public static RealtimeImageProperties off() {
        return new RealtimeImageProperties(false, null, null, null, null, null, null);
    }

    public boolean configured() {
        return enabled && apiKeyId != null && !apiKeyId.isBlank() && apiKeySecret != null && !apiKeySecret.isBlank();
    }
}
