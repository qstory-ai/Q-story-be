package com.qstory.backend.org.dto;

import com.qstory.backend.org.entity.ClassGroup;
import java.time.Instant;
import java.util.UUID;

public record ClassResponse(UUID id, UUID organizationId, String name, String joinCode, Instant createdAt) {

    public static ClassResponse of(ClassGroup classGroup) {
        return new ClassResponse(
                classGroup.getId(), classGroup.getOrganization().getId(), classGroup.getName(),
                classGroup.getJoinCode(), classGroup.getCreatedAt());
    }
}
