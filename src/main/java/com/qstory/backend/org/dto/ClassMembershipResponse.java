package com.qstory.backend.org.dto;

import com.qstory.backend.tutor.entity.TutorStudent;
import java.util.UUID;

/** 학부모가 "내 아이가 들어가 있는 반" 목록에서 보는 한 줄. 담임이 아직 없으면 tutorDisplayName은 null. */
public record ClassMembershipResponse(
        UUID studentId, String studentName, UUID classId, String className, String organizationName,
        String tutorDisplayName) {

    public static ClassMembershipResponse of(TutorStudent student) {
        var classGroup = student.getClassGroup();
        return new ClassMembershipResponse(
                student.getId(), student.getName(),
                classGroup == null ? null : classGroup.getId(),
                classGroup == null ? null : classGroup.getName(),
                classGroup == null || classGroup.getOrganization() == null ? null : classGroup.getOrganization().getName(),
                student.getTutor() == null ? null : student.getTutor().getDisplayName());
    }
}
