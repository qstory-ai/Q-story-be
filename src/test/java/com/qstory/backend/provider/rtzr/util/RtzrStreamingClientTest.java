package com.qstory.backend.provider.rtzr.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RtzrStreamingClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesPartialAndFinalResults() {
        RtzrStreamingClient.Result partial = RtzrStreamingClient.parseResult(objectMapper,
                "{\"seq\":1,\"start_at\":0,\"duration\":0,\"final\":false,\"alternatives\":[{\"text\":\"빵 부스러기를 \"}]}");
        assertEquals(new RtzrStreamingClient.Result(1, false, "빵 부스러기를"), partial);

        RtzrStreamingClient.Result last = RtzrStreamingClient.parseResult(objectMapper,
                "{\"seq\":1,\"final\":true,\"alternatives\":[{\"text\":\"빵 부스러기를 새들이 다 먹어버렸잖아\",\"words\":[]}]}");
        assertTrue(last.isFinal());
        assertEquals("빵 부스러기를 새들이 다 먹어버렸잖아", last.text());

        assertNull(RtzrStreamingClient.parseResult(objectMapper, "not json"));
        assertNull(RtzrStreamingClient.parseResult(objectMapper, "{\"code\":\"E500\"}"));
    }

    @Test
    void keywordsAreCommaJoinedWithoutSeparatorCharacters() {
        assertEquals("그레텔,헨젤,과자 집",
                RtzrStreamingClient.keywordParam(List.of("그레텔", "헨젤", "그레텔", "과자,집", " ")));
        assertEquals("마녀 2", RtzrStreamingClient.keywordParam(List.of("마녀:2")));
        assertEquals("", RtzrStreamingClient.keywordParam(null));
    }

    @Test
    void keywordsAreCapped() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            many.add("단어" + i);
        }
        assertEquals(RtzrStreamingClient.MAX_KEYWORDS, RtzrStreamingClient.keywordParam(many).split(",").length);
    }

    @Test
    void streamUriAsksFor16kLinear16WithBatchPostProcessing() {
        String uri = RtzrStreamingClient.streamUri(List.of("그레텔")).toString();
        assertTrue(uri.startsWith("wss://openapi.vito.ai/v1/transcribe:streaming?sample_rate=16000&encoding=LINEAR16"));
        assertTrue(uri.contains("use_itn=true"));
        assertTrue(uri.contains("use_profanity_filter=true"));
        assertTrue(uri.contains("keywords=%EA%B7%B8%EB%A0%88%ED%85%94"));
    }
}
