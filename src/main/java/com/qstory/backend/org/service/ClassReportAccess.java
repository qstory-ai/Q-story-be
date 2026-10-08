package com.qstory.backend.org.service;

import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.tutor.entity.TutorStudent;
import java.util.UUID;

/**
 * 담임 선생님이 자기 반 학생의 수업 기록 중 무엇을 보는가(076). 자기가 진행한 기록, 그리고 학생이 같은 기관의 다른
 * 반(옮기기 전 지난 반)에서 남긴 기록 - 원장이 학생을 옮겨도 새 담임이 그 아이의 지난 수업을 이어서 본다. 같은 반에서
 * 지난 담임이 진행한 기록은 지난 담임 것으로 남는다(Q-35, 담임 변경 정책 그대로).
 */
public final class ClassReportAccess {

    private ClassReportAccess() {}

    public static boolean visibleToHomeroom(StoryCompletion completion, TutorStudent student, UUID tutorId) {
        if (completion.getUser() != null && completion.getUser().getId().equals(tutorId)) {
            return true;
        }
        ClassGroup current = student.getClassGroup();
        ClassGroup recorded = completion.getClassGroup();
        if (current == null || current.getOrganization() == null || recorded == null) {
            return false;
        }
        if (recorded.getId().equals(current.getId())) {
            return false;
        }
        UUID recordedOrganization = completion.getOrganization() != null
                ? completion.getOrganization().getId()
                : recorded.getOrganization() == null ? null : recorded.getOrganization().getId();
        return current.getOrganization().getId().equals(recordedOrganization);
    }
}
