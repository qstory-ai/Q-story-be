package com.qstory.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HttpClientConfigTest {

    @Test
    void sharedHttpClientGivesUpConnectingAfterFiveSeconds() {
        assertEquals(Optional.of(Duration.ofSeconds(5)), new HttpClientConfig().httpClient().connectTimeout());
    }
}
