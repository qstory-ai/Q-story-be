package com.qstory.backend.clienterror;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientErrorControllerTest {

    @Test
    void cleanStripsQueriesContactsAndNewlines() {
        String cleaned = ClientErrorController.clean(
                "Failed https://x.app/a?token=abc\nmail me@ex.com 01012345678", 300);
        assertThat(cleaned)
                .contains("?[query]")
                .contains("[email]")
                .contains("[number]")
                .doesNotContain("abc")
                .doesNotContain("\n");
    }

    @Test
    void tokenDropsQueryAndRejectsFreeText() {
        assertThat(ClientErrorController.token("/stories/HG/play?childId=123", "x")).isEqualTo("/stories/HG/play");
        assertThat(ClientErrorController.token("아이 이름 철수", "unknown")).isEqualTo("unknown");
    }

    /** 보안 점검 L10: 값 안의 '='로 한 줄 로그(key=value)에 가짜 필드를 끼워 넣지 못한다. */
    @Test
    void cleanEscapesEqualsSigns() {
        assertThat(ClientErrorController.clean("boom kind=RENDER route=/admin", 300))
                .doesNotContain("=")
                .contains("kind%3DRENDER");
    }
}
