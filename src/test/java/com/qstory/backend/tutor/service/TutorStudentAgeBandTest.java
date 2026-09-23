package com.qstory.backend.tutor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 선생님 학생 연령대("7세")를 부모 아이 프로필 구간("6-7")으로 옮기는 규칙 - 프론트
 * entities/child/model/age-band.ts의 ageBandFromLabel과 같은 경계를 지켜야 부모 온보딩 폼의
 * 미리 채움 값과 서버가 만든 아이 프로필이 어긋나지 않는다.
 */
class TutorStudentAgeBandTest {

    @Test
    void mapsSingleAgeLabelsToParentBands() {
        assertEquals("4-5", TutorStudentService.childAgeBandFor("5세"));
        assertEquals("6-7", TutorStudentService.childAgeBandFor("6세"));
        assertEquals("6-7", TutorStudentService.childAgeBandFor("7세"));
        assertEquals("8-9", TutorStudentService.childAgeBandFor("8세"));
        assertEquals("8-9", TutorStudentService.childAgeBandFor("9세"));
        assertEquals("10-11", TutorStudentService.childAgeBandFor("11세"));
        assertEquals("12+", TutorStudentService.childAgeBandFor("13세"));
    }

    @Test
    void rangeLabelsUseTheirFirstNumberAndMissingNumbersFallBack() {
        assertEquals("6-7", TutorStudentService.childAgeBandFor("6-7세"));
        assertEquals("8-9", TutorStudentService.childAgeBandFor("8-9"));
        assertEquals("6-7", TutorStudentService.childAgeBandFor("초등 저학년"));
        assertEquals("6-7", TutorStudentService.childAgeBandFor(null));
    }
}
