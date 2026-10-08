package com.qstory.backend.org.dto;

import com.qstory.backend.org.entity.ClassGroup;
import java.time.Instant;
import java.util.UUID;

/** archivedAt(076): 지난 반으로 보관한 시각, 지금 쓰는 반이면 null. */
public record ClassResponse(
        UUID id, UUID organizationId, UUID tutorId, String name, String joinCode, Instant createdAt, Instant archivedAt) {

    public static ClassResponse of(ClassGroup classGroup) {
        return new ClassResponse(
                classGroup.getId(),
                classGroup.getOrganization() == null ? null : classGroup.getOrganization().getId(),
                classGroup.getTutor() == null ? null : classGroup.getTutor().getId(),
                classGroup.getName(), classGroup.getJoinCode(), classGroup.getCreatedAt(), classGroup.getArchivedAt());
    }

    /** 지난 담임(076)에게 보이는 반 - 더 이상 맡지 않는 반이라 학부모를 들일 수 있는 반 코드는 빼고 보낸다. */
    public ClassResponse withoutJoinCode() {
        return new ClassResponse(id, organizationId, tutorId, name, null, createdAt, archivedAt);
    }
}
