package com.qstory.backend.tutor.dto;

import java.time.Instant;

/** token은 한 번만 노출되는 원본 시크릿이다 - 저장되는 것은 항상 해시값뿐이다(TutorInvite 참고). */
public record TutorInviteResponse(String token, Instant expiresAt) {}
