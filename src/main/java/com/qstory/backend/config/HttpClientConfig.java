package com.qstory.backend.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 범용 아웃바운드 HTTP 클라이언트 빈들. restClient()는 지금 GoogleOAuthVerifier/
 * KakaoOAuthVerifier만 쓴다.
 *
 * <p>httpClient()는 이 커밋 이전부터 OpenRouterClient가 생성자로 요구하고 있었지만
 * (java.net.http.HttpClient httpClient 파라미터) 그 타입의 빈이 어디에도 정의되어 있지
 * 않아서, 이 클래스를 추가하며 백엔드를 재빌드해보기 전까지는 앱이 아예 기동조차 못 하는
 * 상태였다(UnsatisfiedDependencyException) - OAuth 작업과는 무관하지만 부팅 자체를 막고
 * 있어서 여기서 함께 채웠다.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newHttpClient();
    }
}
