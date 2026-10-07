package com.qstory.backend.identity.dto;

/** 가입 요청에 함께 오는 동의 - terms·privacy는 필수, marketing은 선택. version은 약관 버전(예: 2026-10-v1). */
public record ConsentPayload(String version, boolean terms, boolean privacy, boolean marketing) {}
