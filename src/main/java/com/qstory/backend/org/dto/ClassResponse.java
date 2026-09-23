package com.qstory.backend.org.dto;

import com.qstory.backend.org.entity.ClassGroup;
import java.time.Instant;
import java.util.UUID;

public record ClassResponse(UUID id, UUID organizationId, UUID tutorId, String name, String joinCode, Instant createdAt) {

    public static ClassResponse of(ClassGroup classGroup) {
        return new ClassResponse(
                classGroup.getId(),
                classGroup.getOrganization() == null ? null : classGroup.getOrganization().getId(),
                classGroup.getTutor() == null ? null : classGroup.getTutor().getId(),
                classGroup.getName(), classGroup.getJoinCode(), classGroup.getCreatedAt());
    }
}
