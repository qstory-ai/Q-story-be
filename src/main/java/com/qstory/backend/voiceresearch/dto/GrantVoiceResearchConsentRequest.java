package com.qstory.backend.voiceresearch.dto;

/**
 * 보호자가 음성 원본 보관에 동의할 때 - 화면에 보여 준 약관 버전을 그대로 보내, 서버의 현재 버전과 같을 때만 받는다.
 * source는 선택(ONBOARDING | MYPAGE 등), 없으면 MYPAGE.
 */
public record GrantVoiceResearchConsentRequest(String consentVersion, String source) {}
