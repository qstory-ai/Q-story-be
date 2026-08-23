package com.qstory.backend.org.dto;

import java.time.Instant;

/** token is the raw, one-time-visible secret - only its hash is ever persisted (see ClassInvite). */
public record ClassInviteResponse(String token, Instant expiresAt) {}
