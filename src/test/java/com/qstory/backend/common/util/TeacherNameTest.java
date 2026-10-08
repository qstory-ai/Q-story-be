package com.qstory.backend.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TeacherNameTest {

    @Test
    void addsTheTitleOnlyWhenTheNameDoesNotAlreadyHaveIt() {
        assertEquals("김하나 선생님", TeacherName.of("김하나"));
        assertEquals("새 담임 선생님", TeacherName.of("새 담임 선생님"));
        assertEquals("김선생님", TeacherName.of("김선생"));
        assertEquals("선생님", TeacherName.of("  "));
        assertEquals("선생님", TeacherName.of(null));
    }
}
