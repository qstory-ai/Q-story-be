package com.qstory.backend.org.dto;

/** POST /v1/organizations/{orgId}/guardian-consent-ack - 이미 있는 기관의 보호자 동의 확인(다시 동의 화면). */
public record GuardianConsentAckRequest(String consentVersion) {}
