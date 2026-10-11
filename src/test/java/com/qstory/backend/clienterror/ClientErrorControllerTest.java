package com.qstory.backend.clienterror;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void rateCapDropsAfterLimit() {
        ClientErrorController controller = new ClientErrorController(new ObjectMapper());
        int allowed = 0;
        for (int i = 0; i < ClientErrorController.MAX_PER_MINUTE + 30; i++) {
            if (controller.allow("10.0.0.1")) allowed++;
        }
        assertThat(allowed).isEqualTo(ClientErrorController.MAX_PER_MINUTE);
    }

    /** 보안 점검 L10: 한 주소가 상한을 다 써도 다른 주소의 에러는 남는다. 서버 전체 상한은 따로 있다. */
    @Test
    void rateCapIsPerClientWithAServerWideCeiling() {
        ClientErrorController controller = new ClientErrorController(new ObjectMapper());
        for (int i = 0; i < ClientErrorController.MAX_PER_MINUTE + 10; i++) controller.allow("10.0.0.1");
        assertThat(controller.allow("10.0.0.2")).isTrue();

        int allowed = 0;
        for (int i = 0; i < ClientErrorController.MAX_TOTAL_PER_MINUTE * 2; i++) {
            if (controller.allow("10.0.1." + i)) allowed++;
        }
        // 위에서 이미 쓴 121건(10.0.0.1의 120 + 10.0.0.2의 1)을 빼고 전체 상한까지만.
        assertThat(allowed).isEqualTo(ClientErrorController.MAX_TOTAL_PER_MINUTE - ClientErrorController.MAX_PER_MINUTE - 1);
    }

    /** 보안 점검 L10: 값 안의 '='로 한 줄 로그(key=value)에 가짜 필드를 끼워 넣지 못한다. */
    @Test
    void cleanEscapesEqualsSigns() {
        assertThat(ClientErrorController.clean("boom kind=RENDER route=/admin", 300))
                .doesNotContain("=")
                .contains("kind%3DRENDER");
    }
}
