package com.qstory.backend.org.dto;

import com.qstory.backend.org.entity.ClassGroup;

/**
 * 반 초대 링크를 연 학부모가 가입·로그인 전에 보는 정보 - 어느 기관의 어느 반, 담임 선생님이 누구인지.
 * 기관 없이 선생님이 운영하는 반은 organizationName이 null, 담임 미정인 기관 반은 tutorDisplayName이 null이다.
 */
public record ClassPreviewResponse(String classCode, String className, String organizationName, String tutorDisplayName) {

    public static ClassPreviewResponse of(ClassGroup classGroup) {
        return new ClassPreviewResponse(
                classGroup.getJoinCode(),
                classGroup.getName(),
                classGroup.getOrganization() == null ? null : classGroup.getOrganization().getName(),
                classGroup.getTutor() == null ? null : classGroup.getTutor().getDisplayName());
    }
}
