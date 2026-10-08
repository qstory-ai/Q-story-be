package com.qstory.backend.org.dto;

import com.qstory.backend.org.entity.ClassHomeroomInvite;
import java.time.Instant;
import java.util.UUID;

/** 담임 초대 - 원장이 선생님에게 링크(token) 또는 코드(shortCode)로 건넨다. */
public record ClassHomeroomInviteResponse(UUID id, String token, String shortCode, Instant expiresAt) {

    public static ClassHomeroomInviteResponse of(ClassHomeroomInvite invite) {
        return new ClassHomeroomInviteResponse(
                invite.getId(), invite.getToken(), invite.getShortCode(), invite.getExpiresAt());
    }
}
