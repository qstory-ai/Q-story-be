package com.qstory.backend.livebranch.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LiveBranchExecutionWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void knownAnchorIdsReadsEveryAnchorSegmentOfTheAssembledStory() throws Exception {
        var assembled = objectMapper.readTree("""
                {"generatedContent": {"scenes": [
                  {"id": "HG-F04", "anchors": ["HG-F04-CANDY-HOUSE-REVEAL"]},
                  {"id": "HG-F05", "anchors": []},
                  {"id": "HG-F07", "anchors": ["HG-F07-KEYS-TAKEN", ""]}
                ]}}
                """);
        assertThat(LiveBranchExecutionWorker.knownAnchorIds(assembled))
                .containsExactly("HG-F04-CANDY-HOUSE-REVEAL", "HG-F07-KEYS-TAKEN");
        // 옛 이름(HG-F07-DEMONSTRATION)은 후보에서 걸러져야 한다.
        assertThat(LiveBranchExecutionWorker.knownAnchorIds(assembled)).doesNotContain("HG-F07-DEMONSTRATION");
    }

    @Test
    void knownAnchorIdsIsEmptyWhenNothingIsAssembled() {
        assertThat(LiveBranchExecutionWorker.knownAnchorIds(null)).isEmpty();
    }
}
