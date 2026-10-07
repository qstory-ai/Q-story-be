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
            if (controller.allow()) allowed++;
        }
        assertThat(allowed).isEqualTo(ClientErrorController.MAX_PER_MINUTE);
    }
}
