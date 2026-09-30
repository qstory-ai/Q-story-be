package com.qstory.backend.tutor.dto;

/** 초대를 아직 소비하지 않고 미리 보여준다 - 부모가 계정을 만들기 전에 누가·어떤 아이로 보냈는지 먼저 확인한다. */
public record TutorInvitePreviewResponse(
        String studentName, String ageBand, String tutorDisplayName, Integer birthYear,
        /** 반 학생이면 반 이름, 기관 반이면 기관 이름 - 부모가 "어느 유치원 어느 반"의 초대인지 알아보게. */
        String className, String organizationName) {}
