package com.qstory.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qstory")
public record AppProperties(
        List<String> allowedOrigins,
        /**
         * 태블릿·폰 네이티브 앱(fe/q-story-web을 Capacitor로 감싼 것)의 WebView 출처. allowedOrigins와
         * 분리한 이유: 프로필별 application-*.yml과 ALLOWED_ORIGINS 환경변수가 allowedOrigins를 통째로
         * 덮어쓰므로, 거기에 끼워 넣으면 네 파일과 배포 환경변수를 전부 손으로 맞춰야 한다. 이 목록은
         * application.yml에서만 정하고 SecurityConfig가 allowedOrigins에 더한다.
         */
        List<String> nativeOrigins,
        long maxAudioBytes,
        long requestTimeoutMs,
        String ffmpegPath,
        Providers providers,
        Supabase supabase,
        Admin admin,
        Auth auth,
        Payments payments,
        ConversationRecord conversationRecord) {

    /** 대화 원장(conversation_record) 보존 일수. 0 이하면 만료 삭제를 하지 않는다. */
    public record ConversationRecord(int retentionDays) {}

    public record Payments(Toss toss) {}

    public record Toss(String secretKey, int parentMonthlyAmount, int organizationMonthlyAmount, int accessDays) {
        public boolean configured() {
            return secretKey != null && !secretKey.isBlank();
        }
    }

    public record Admin(String storyImportToken) {
        public boolean storyImportTokenConfigured() {
            return storyImportToken != null && !storyImportToken.isBlank();
        }
    }

    public record Auth(String jwtSecret, long accessTokenTtlMinutes) {
        public boolean configured() {
            return jwtSecret != null && !jwtSecret.isBlank();
        }
    }

    public record Providers(Rtzr rtzr, OpenRouter openRouter, Gemini gemini, Oauth oauth) {}

    public record Rtzr(String clientId, String clientSecret) {
        public boolean configured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public record Oauth(Google google, Kakao kakao) {}

    public record Google(String clientId) {
        public boolean configured() {
            return clientId != null && !clientId.isBlank();
        }
    }

    public record Kakao(String appId) {
        public boolean configured() {
            return appId != null && !appId.isBlank();
        }
    }

    public record OpenRouter(
            String apiKey, String llmModel, String safetyModel, String imageModel) {
        public boolean llmConfigured() {
            return notBlank(apiKey) && notBlank(llmModel);
        }

        public boolean imageConfigured() {
            return notBlank(apiKey) && notBlank(imageModel);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    /** TTS 전용 - OpenRouter 카탈로그에 없는 Gemini 자체 모델/목소리를 쓰므로 Gemini API를 직접 호출한다. */
    public record Gemini(String apiKey, String ttsModel, String ttsVoice) {
        public boolean ttsConfigured() {
            return notBlank(apiKey) && notBlank(ttsModel) && notBlank(ttsVoice);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record Supabase(
            String url,
            String serviceRoleKey,
            String voiceResearchBucket,
            String shadowAssetsBucket,
            String storyAudioBucket,
            String storyImageBucket,
            String profileImageBucket,
            String voiceResearchCleanupToken) {
        public boolean configured() {
            return url != null && !url.isBlank()
                    && serviceRoleKey != null && !serviceRoleKey.isBlank();
        }
    }
}
