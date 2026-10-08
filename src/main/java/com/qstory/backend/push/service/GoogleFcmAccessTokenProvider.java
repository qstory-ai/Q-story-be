package com.qstory.backend.push.service;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 서비스 계정 키로 firebase.messaging 범위의 액세스 토큰을 받는다. GoogleCredentials가 토큰을 들고 있다가 만료가
 * 가까워질 때만(기본 5분 전) 다시 받으므로, 발송마다 토큰 교환을 하지 않는다.
 */
final class GoogleFcmAccessTokenProvider implements FcmAccessTokenProvider {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final GoogleCredentials credentials;

    GoogleFcmAccessTokenProvider(String serviceAccountJson) throws IOException {
        // ServiceAccountCredentials로 읽어 service_account 형식만 받는다(다른 형식의 자격 증명 JSON은 거부).
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
