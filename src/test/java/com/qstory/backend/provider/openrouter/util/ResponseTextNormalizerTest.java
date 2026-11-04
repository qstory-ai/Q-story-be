package com.qstory.backend.provider.openrouter.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResponseTextNormalizerTest {

    private final ResponseTextNormalizer normalizer = new ResponseTextNormalizer();

    @Test
    void fixesKnownMisspellingsAndLeavesTheRestAlone() {
        assertThat(normalizer.normalizeKoreanResponseText("새가 날개를 날개짓하며 살쳐보다 다가지지 않았어."))
                .isEqualTo("새가 날갯짓하며 살펴보다 다가가지 않았어.");
        assertThat(normalizer.normalizeKoreanResponseText("그대로야.")).isEqualTo("그대로야.");
    }
}
