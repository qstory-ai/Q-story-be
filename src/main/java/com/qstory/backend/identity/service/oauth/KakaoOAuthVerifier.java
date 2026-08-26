package com.qstory.backend.identity.service.oauth;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 프론트가 카카오 JS SDK로 받은 access token을 그대로 보내면, 여기서 카카오의
 * access_token_info로 이 토큰이 우리 앱(app-id) 앞으로 발급된 게 맞는지 먼저 확인한 뒤에만
 * /v2/user/me로 실제 프로필을 가져온다. app-id 확인을 건너뛰면, 전혀 다른(공격자가 만든) 카카오
 * 앱에 로그인해서 발급받은 토큰을 그대로 우리 서비스에 재사용해 그 사용자로 가입/로그인할 수
 * 있게 된다 - GoogleOAuthVerifier의 aud 검증과 같은 이유다.
 */
@Component
public class KakaoOAuthVerifier {

    private static final String TOKEN_INFO_URL = "https://kapi.kakao.com/v1/user/access_token_info";
    private static final String USER_ME_URL = "https://kapi.kakao.com/v2/user/me";

    private final RestClient restClient;
    private final AppProperties config;

    public KakaoOAuthVerifier(RestClient restClient, AppProperties config) {
        this.restClient = restClient;
        this.config = config;
    }

    public record KakaoIdentity(String subject, String email, String displayName) {}

    public KakaoIdentity verify(String accessToken) {
        AppProperties.Kakao kakao = config.providers().oauth().kakao();
        if (!kakao.configured()) {
            throw ApiException.contractError(
                    ErrorCode.OAUTH_PROVIDER_NOT_CONFIGURED, "카카오 로그인이 아직 설정되지 않았어요.", 503);
        }

        Map<String, Object> tokenInfo = callKakao(TOKEN_INFO_URL, accessToken);
        if (tokenInfo == null || !kakao.appId().equals(String.valueOf(tokenInfo.get("app_id")))) {
            throw ApiException.contractError(ErrorCode.OAUTH_TOKEN_INVALID, "카카오 로그인 확인에 실패했어요.", 401);
        }

        Map<String, Object> profile = callKakao(USER_ME_URL, accessToken);
        Object subject = profile == null ? null : profile.get("id");
        if (subject == null) {
            throw ApiException.contractError(ErrorCode.OAUTH_TOKEN_INVALID, "카카오 로그인 확인에 실패했어요.", 401);
        }

        String email = null;
        String displayName = null;
        Object accountObj = profile.get("kakao_account");
        if (accountObj instanceof Map<?, ?> account) {
            Object emailValue = account.get("email");
            email = emailValue == null ? null : String.valueOf(emailValue);
            Object profileObj = account.get("profile");
            if (profileObj instanceof Map<?, ?> profileMap) {
                Object nickname = profileMap.get("nickname");
                displayName = nickname == null ? null : String.valueOf(nickname);
            }
        }
        return new KakaoIdentity(String.valueOf(subject), email, displayName);
    }

    private Map<String, Object> callKakao(String url, String accessToken) {
        try {
            return restClient.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientException failure) {
            throw ApiException.contractError(ErrorCode.OAUTH_TOKEN_INVALID, "카카오 로그인 확인에 실패했어요.", 401);
        }
    }
}
