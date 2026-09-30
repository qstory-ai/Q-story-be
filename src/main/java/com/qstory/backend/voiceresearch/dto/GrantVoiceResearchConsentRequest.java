package com.qstory.backend.voiceresearch.dto;

/** 마이페이지에서 다시 동의할 때 - 화면에 보여 준 약관 버전을 그대로 보내, 서버의 현재 버전과 같을 때만 받는다. */
public record GrantVoiceResearchConsentRequest(String consentVersion) {}
