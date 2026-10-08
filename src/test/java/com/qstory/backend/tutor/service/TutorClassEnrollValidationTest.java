package com.qstory.backend.tutor.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.qstory.backend.common.error.ApiException;
import org.junit.jupiter.api.Test;

/** 선생님 운영 반 가입은 아이 이름·출생연도 없이는 저장소를 건드리기 전에 거절된다. */
class TutorClassEnrollValidationTest {

    private final TutorStudentService service =
            new TutorStudentService(null, null, null, null, null, null);

    @Test
    void rejectsMissingChildName() {
        assertThrows(ApiException.class, () -> service.enrollParentInClass(null, null, "  ", 2019, null, null));
        assertThrows(ApiException.class, () -> service.enrollParentInClass(null, null, null, 2019, null, null));
    }

    @Test
    void rejectsMissingBirthYear() {
        assertThrows(ApiException.class, () -> service.enrollParentInClass(null, null, "민서", null, null, null));
    }
}
