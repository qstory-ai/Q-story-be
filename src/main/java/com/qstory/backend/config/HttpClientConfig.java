package com.qstory.backend.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 공유 아웃바운드 HTTP 클라이언트 빈. restClient()는 OAuth 검증(OAuthHttpClient), httpClient()는
 * provider 클라이언트들과 SupabaseStorageClient가 쓴다.
 *
 * <p>연결 타임아웃 5초 - 기본값(없음)이면 상대 서버가 SYN에 답하지 않을 때 OS 타임아웃(수십 초~2분)까지 요청 스레드가
 * 묶인다. 응답 시간 상한은 요청마다 HttpRequest.timeout()으로 따로 건다(provider별 예산이 다르다). RestClient는
 * OAuth 토큰 확인 두세 곳만 쓰므로 읽기 타임아웃 10초를 같이 건다.
 */
@Configuration
public class HttpClientConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REST_READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public RestClient restClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(newHttpClient());
        factory.setReadTimeout(REST_READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    public HttpClient httpClient() {
        return newHttpClient();
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }
}
