package com.qstory.backend.analytics.service;

import java.io.IOException;

/** GA Data API 호출용 OAuth2 액세스 토큰. 테스트에서는 고정 값으로 바꾼다. */
public interface GaAccessTokenProvider {

    String accessToken() throws IOException;
}
