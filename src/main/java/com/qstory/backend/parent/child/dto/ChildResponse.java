package com.qstory.backend.parent.child.dto;

import com.qstory.backend.parent.child.entity.Child;
import java.time.Instant;
import java.util.UUID;

public record ChildResponse(
        UUID id, String name, String ageBand, String avatarKey, String gender,
        Instant createdAt, Instant updatedAt) {

    public static ChildResponse of(Child child) {
        return new ChildResponse(
                child.getId(), child.getName(), child.getAgeBand(), child.getAvatarKey(),
                child.getGender(), child.getCreatedAt(), child.getUpdatedAt());
    }
}
