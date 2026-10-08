package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KoreanParticleTest {

    @Test
    void subjectAndDirectionFollowTheFinalConsonant() {
        assertEquals("민서가", KoreanParticle.subject("민서"));
        assertEquals("하준이", KoreanParticle.subject("하준"));
        assertEquals("Leo이(가)", KoreanParticle.subject("Leo"));
        assertEquals("햇님반으로", KoreanParticle.direction("햇님반"));
        assertEquals("나무로", KoreanParticle.direction("나무"));
        assertEquals("하늘로", KoreanParticle.direction("하늘"));
        assertEquals("7(으)로", KoreanParticle.direction("7"));
    }
}
