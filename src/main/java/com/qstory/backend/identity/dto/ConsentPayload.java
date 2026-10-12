package com.qstory.backend.identity.dto;

/**
 * 가입 요청에 함께 오는 동의. version은 약관 버전(예: 2026-10-v2).
 *
 * <ul>
 *   <li>terms·privacy·overseasTransfer(개인정보 국외 이전) - 모든 가입에 필수
 *   <li>guardian(만 14세 미만 아이의 법정대리인 확인) - 보호자(PARENT) 가입에 필수
 *   <li>marketing - 선택
 * </ul>
 * guardian·overseasTransfer가 없는 예전 클라이언트의 요청은 false로 읽혀 400 CONSENT_REQUIRED가 된다.
 */
public record ConsentPayload(
        String version, boolean terms, boolean privacy, boolean marketing, boolean guardian, boolean overseasTransfer) {}
