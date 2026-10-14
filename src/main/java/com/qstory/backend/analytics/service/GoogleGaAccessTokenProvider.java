package com.qstory.backend.analytics.service;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 서비스 계정 키로 analytics.readonly 범위의 액세스 토큰을 받는다(푸시의 GoogleFcmAccessTokenProvider와 같은 방식).
 * 만료가 가까워질 때만 다시 받는다.
 */
final class GoogleGaAccessTokenProvider implements GaAccessTokenProvider {

    static final String SCOPE = "https://www.googleapis.com/auth/analytics.readonly";

    private final GoogleCredentials credentials;

    GoogleGaAccessTokenProvider(String serviceAccountJson) throws IOException {
        this.credentials = ServiceAccountCredentials
                .fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)))
                .createScoped(List.of(SCOPE));
    }

    @Override
    public synchronized String accessToken() throws IOException {
        credentials.refreshIfExpired();
        AccessToken token = credentials.getAccessToken();
        if (token == null || token.getTokenValue() == null) {
            throw new IOException("no access token");
        }
        return token.getTokenValue();
    }
}
