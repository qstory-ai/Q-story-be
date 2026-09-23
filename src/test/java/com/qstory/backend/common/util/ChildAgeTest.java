package com.qstory.backend.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.qstory.backend.common.error.ApiException;
import org.junit.jupiter.api.Test;

/** 출생연도 → 연 나이 → 부모 구간/선생님 라벨. 프론트 age-band.ts의 ageFromBirthYear·ageBandFromLabel과 같은 경계. */
class ChildAgeTest {

    @Test
    void ageIsYearDifference() {
        int year = ChildAge.currentYear();
        assertEquals(7, ChildAge.ageInYears(year - 7));
        assertEquals(0, ChildAge.ageInYears(year));
        assertEquals("7세", ChildAge.tutorLabel(year - 7));
    }

    @Test
    void parentBandFollowsTheSameBoundariesAsTheFrontend() {
        int year = ChildAge.currentYear();
        assertEquals("4-5", ChildAge.parentBand(year - 5));
        assertEquals("6-7", ChildAge.parentBand(year - 6));
        assertEquals("6-7", ChildAge.parentBand(year - 7));
        assertEquals("8-9", ChildAge.parentBand(year - 8));
        assertEquals("10-11", ChildAge.parentBand(year - 11));
        assertEquals("12+", ChildAge.parentBand(year - 13));
    }

    @Test
    void birthYearOutsideTheServiceRangeIsRejected() {
        int year = ChildAge.currentYear();
        assertNull(ChildAge.validateBirthYear(null));
        assertEquals(year - 7, ChildAge.validateBirthYear(year - 7));
        assertThrows(ApiException.class, () -> ChildAge.validateBirthYear(year + 1));
        assertThrows(ApiException.class, () -> ChildAge.validateBirthYear(year - 15));
    }
}
