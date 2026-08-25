package com.qstory.backend.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient providerHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Spring Boot가 자동 구성한 ObjectMapper 빈(Spring MVC의 JSON 메시지 컨버터가 여전히 사용함)을
     * 오버라이드하여, java.time 타입이 예외를 던지는 대신 ISO-8601로 직렬화되도록 한다. 여기 있는
     * 모든 컨트롤러는 바로 이 인스턴스를 통해 HttpJsonWriter로 JSON을 수동으로 작성하므로, 두
     * 경로(수동 작성과, 바디를 직접 반환하는 소수의 @RestController 메서드) 사이의 출력이
     * 바이트 단위로 일치하게 유지된다.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
