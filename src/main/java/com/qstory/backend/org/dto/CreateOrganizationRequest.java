package com.qstory.backend.org.dto;

/**
 * guardianConsentAck - "원아의 보호자 동의를 받아 이용해요(기관이 보호자에게 안내·동의를 받습니다)" 확인(필수, A5).
 * consentVersion - 그 문구의 약관 버전(예: 2026-10-v2).
 */
public record CreateOrganizationRequest(String name, Boolean guardianConsentAck, String consentVersion) {}
