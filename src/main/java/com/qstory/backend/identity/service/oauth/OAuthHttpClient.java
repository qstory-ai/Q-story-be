package com.qstory.backend.identity.service.oauth;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GoogleOAuthVerifier/KakaoOAuthVerifier가 각자 따로 갖고 있던 "provider에 GET 요청을 보내고
 * 실패하면 OAUTH_TOKEN_INVALID로 변환한다" 로직을 하나로 모았다.
 */
@Component
public class OAuthHttpClient {

    private final RestClient restClient;

    public OAuthHttpClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** 쿼리 파라미터로 토큰을 전달하는 provider용 - Google tokeninfo가 이 형태다. */
    public Map<String, Object> get(String uriTemplate, String safeDetail, Object... uriVariables) {
        try {
            return restClient.get()
                    .uri(uriTemplate, uriVariables)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientException failure) {
            throw ApiException.contractError(ErrorCode.OAUTH_TOKEN_INVALID, safeDetail, 401);
        }
    }

    /** Authorization: Bearer 헤더로 토큰을 전달하는 provider용 - Kakao의 두 엔드포인트가 이 형태다. */
    public Map<String, Object> getWithBearer(String url, String bearerToken, String safeDetail) {
        try {
            return restClient.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientException failure) {
            throw ApiException.contractError(ErrorCode.OAUTH_TOKEN_INVALID, safeDetail, 401);
        }
    }
}
