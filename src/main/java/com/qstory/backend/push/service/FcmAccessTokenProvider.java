package com.qstory.backend.push.service;

import java.io.IOException;

/** FCM HTTP v1 호출용 OAuth2 액세스 토큰. 테스트가 바꿔 끼울 수 있게 FcmClient와 분리했다. */
@FunctionalInterface
public interface FcmAccessTokenProvider {

    String accessToken() throws IOException;
}
