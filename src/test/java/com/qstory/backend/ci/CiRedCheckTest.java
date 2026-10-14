package com.qstory.backend.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CiRedCheckTest {
    @Test
    void failsOnPurpose() {
        assertEquals(1, 2, "CI 빨간불 확인용 - 병합 금지");
    }
}
