package com.qstory.backend.betaevents.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BetaEventValidatorDialogueTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BetaEventValidator validator = new BetaEventValidator();

    private JsonNode event(String metadata) throws Exception {
        return mapper.readTree("""
                {"event_id":"%s","session_id":"%s","event_name":"dialogue_step","source":"player",
                 "occurred_at":"%s","schema_version":1,"metadata":%s}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), metadata));
    }

    @Test
    void dialogueStepAcceptsItsMetadata() throws Exception {
        var parsed = validator.parse(event("""
                {"anchor_id":"HG-Q-C","scene_id":"HG-F08","entry_mode":"INVITE","turn_kind":"CONFIRM",
                 "family_id":"C_WAIT_FOR_WITCH_TURN","via_suggestion":true,"help_step":4,"turn_number":2,
                 "reply_kind":"ANSWER","elapsed_ms":3200}
                """));
        assertThat(parsed.eventName()).isEqualTo(EventName.DIALOGUE_STEP);
        assertThat(parsed.metadata()).containsEntry("via_suggestion", true).containsEntry("help_step", 4L);
    }

    @Test
    void dialogueStepAcceptsTranscriptionLatency() throws Exception {
        var parsed = validator.parse(event("""
                {"entry_mode":"SPONTANEOUS","turn_kind":"TRANSCRIBED","stt_mode":"STREAM","stt_ms":120,
                 "stream_tried":true,"elapsed_ms":4100}
                """));
        assertThat(parsed.metadata()).containsEntry("stt_mode", "STREAM").containsEntry("stt_ms", 120L);
    }

    @Test
    void dialogueStepAcceptsThinkingFiller() throws Exception {
        var parsed = validator.parse(event("""
                {"entry_mode":"SPONTANEOUS","turn_kind":"FILLER","filler_clip":"gretel-thinking-2","filler_late_ms":28,
                 "elapsed_ms":1050}
                """));
        assertThat(parsed.metadata()).containsEntry("filler_clip", "gretel-thinking-2").containsEntry("filler_late_ms", 28L);
    }

    @Test
    void dialogueStepRejectsChildUtteranceText() {
        assertThatThrownBy(() -> validator.parse(event("{\"question_text\":\"마녀는 왜 나빠?\"}")))
                .isInstanceOf(ApiException.class);
    }
}
