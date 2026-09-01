package com.qstory.backend.tutor.dto;

import java.time.Instant;

/**
 * 초대 발급 응답. token은 한 번만 노출되는 원본 시크릿이라(저장은 항상 해시값뿐, TutorInvite 참고)
 * 이 응답을 놓치면 다시 볼 수 없다 - 클라이언트는 즉시 UI에 노출해서 복사하도록 해야 한다.
 * shortCode는 사람이 손으로 옮길 수 있는 짧은 형태로, 링크(token)와 같은 초대를 가리킨다 -
 * 원본 값이지만 링크와 달리 그 자체가 시크릿은 아니라 안전한 채널로만 공유될 필요는 없다.
 */
public record TutorInviteResponse(String token, String shortCode, Instant expiresAt) {}
