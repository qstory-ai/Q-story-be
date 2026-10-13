package com.qstory.backend.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContactInfoMaskerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "010-1234-5678", "010 1234 5678", "010.1234.5678", "01012345678", "010-123-4567",
            "011-234-5678", "010 - 1234 - 5678", "+82 10-1234-5678", "+821012345678",
            "02-123-4567", "02 1234 5678", "031-123-4567", "0311234567", "051.234.5678", "070-1234-5678"})
    void phoneNumbersAreMasked(String phone) {
        assertEquals("엄마 번호는 [전화번호]이야", ContactInfoMasker.mask("엄마 번호는 " + phone + "이야"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mom@naver.com", "kid.01@gmail.co.kr", "a_b+c@test.io", "mom @ naver.com"})
    void emailsAreMasked(String email) {
        assertEquals("메일은 [이메일] 이야", ContactInfoMasker.mask("메일은 " + email + " 이야"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"150101-3123456", "1501013123456", "991231 - 2123456"})
    void residentNumbersAreMasked(String rrn) {
        assertEquals("내 번호 [주민번호]", ContactInfoMasker.mask("내 번호 " + rrn));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "3번 문으로 갈래", "늑대가 10마리 있었어", "2026년 10월 12일에 갔어", "1234", "과자 100개 200개 300개",
            "010", "0.5초만 기다려", "10:30에 일어났어", "12-34", "숫자 1000000000000", "우리 집은 3층이야",
            "1588-1234", "헨젤이 7살이래", "2015년생이야"})
    void storyNumbersStayAsIs(String text) {
        assertEquals(text, ContactInfoMasker.mask(text));
    }

    @Test
    void emailWithDigitsIsMaskedAsEmailNotPhone() {
        assertEquals("[이메일]", ContactInfoMasker.mask("kid01012345678@naver.com"));
    }

    @Test
    void multipleItemsInOneSentence() {
        assertEquals("[전화번호]나 [이메일]로 연락해, 그리고 사과 3개",
                ContactInfoMasker.mask("010-1111-2222나 a@b.com로 연락해, 그리고 사과 3개"));
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertNull(ContactInfoMasker.mask(null));
        assertEquals("", ContactInfoMasker.mask(""));
        assertEquals("내 이름은 민지야", ContactInfoMasker.mask("내 이름은 민지야"));
    }
}
