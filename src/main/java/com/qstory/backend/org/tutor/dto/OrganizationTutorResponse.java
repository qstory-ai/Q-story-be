package com.qstory.backend.org.tutor.dto;

import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import java.time.Instant;
import java.util.UUID;

public record OrganizationTutorResponse(
        UUID id, UUID tutorId, String tutorDisplayName, String tutorEmail, Instant joinedAt) {

    public static OrganizationTutorResponse of(OrganizationTutor link) {
        var tutor = link.getTutor();
        return new OrganizationTutorResponse(
                link.getId(), tutor.getId(), tutor.getDisplayName(), tutor.getEmail(), link.getJoinedAt());
    }
}
