package com.qstory.backend.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 공유 아웃바운드 HTTP 클라이언트 빈. restClient()는 OAuth 검증(OAuthHttpClient), httpClient()는
 * provider 클라이언트들과 SupabaseStorageClient가 쓴다.
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
