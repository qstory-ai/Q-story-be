package com.qstory.backend.voiceresearch.dto;

import java.time.Instant;

/**
 * 마이페이지에 보여 줄 계정 단위 음성 연구 동의 상태.
 *
 * @param enabled 지금 이 계정으로 올린 질문 원음이 연구용으로 저장되는지
 * @param explicit 보호자가 마이페이지에서 직접 켜거나 끈 기록이 있는지 - false면 서버 기본값을 따르는 중
 * @param consentVersion 마지막으로 동의한 약관 버전(없으면 null)
 * @param consentedAt 마지막으로 동의한 시각(없으면 null)
 * @param withdrawnAt 마지막으로 철회한 시각(다시 동의했거나 철회한 적 없으면 null)
 * @param retentionDays 녹음 보관 기간(일)
 */
public record VoiceResearchConsentStatusResponse(
        boolean enabled,
        boolean explicit,
        String consentVersion,
        Instant consentedAt,
        Instant withdrawnAt,
        int retentionDays) {}
