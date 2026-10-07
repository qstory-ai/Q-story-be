package com.qstory.backend.betaevents.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.enums.EventSource;
import com.qstory.backend.common.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Q-40 UT 이벤트 - 앱 화면 이벤트는 APP 출처로만, 정해진 키만 받는다. */
class BetaEventValidatorUtTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BetaEventValidator validator = new BetaEventValidator();

    private JsonNode event(String name, String source, String metadata) throws Exception {
        return mapper.readTree("""
                {"event_id":"%s","session_id":"%s","event_name":"%s","source":"%s",
                 "occurred_at":"%s","schema_version":1,"metadata":%s}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), name, source, Instant.now(), metadata));
    }

    @Test
    void appEventsAreAcceptedFromTheAppSource() throws Exception {
        var parsed = validator.parse(event("class_join", "app", "{\"step\":\"error\",\"error_code\":\"CODE_NOT_FOUND\",\"via\":\"code_input\"}"));
        assertThat(parsed.eventName()).isEqualTo(EventName.CLASS_JOIN);
        assertThat(parsed.source()).isEqualTo(EventSource.APP);

        var report = validator.parse(event("report_viewed", "app",
                "{\"kind\":\"HOME\",\"source\":\"live\",\"completion_id\":\"" + UUID.randomUUID() + "\",\"viewer_role\":\"PARENT\"}"));
        assertThat(report.metadata()).containsKey("completion_id");

        var started = validator.parse(event("story_started", "player",
                "{\"resume\":false,\"entry_source\":\"home_hero\",\"play_setting\":\"HOME\",\"play_session_id\":\"" + UUID.randomUUID() + "\"}"));
        assertThat(started.metadata()).containsEntry("play_setting", "HOME");
    }

    @Test
    void appEventsFromTheWrongSourceOrWithUnknownKeysAreRejected() {
        assertThatThrownBy(() -> validator.parse(event("signup_started", "player", "{\"role\":\"PARENT\"}")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.parse(event("child_registered", "app", "{\"name\":\"서아\"}")))
                .isInstanceOf(ApiException.class);
    }
}
