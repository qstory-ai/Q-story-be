package com.qstory.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qstory")
public record AppProperties(
        List<String> allowedOrigins,
        long maxAudioBytes,
        long requestTimeoutMs,
        String ffmpegPath,
        Providers providers,
        Supabase supabase,
        Admin admin,
        Auth auth) {

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

    public record Providers(Rtzr rtzr, OpenRouter openRouter, Oauth oauth) {}

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
            String apiKey, String llmModel, String safetyModel, String ttsModel, String ttsVoice,
            String imageModel) {
        public boolean llmConfigured() {
            return notBlank(apiKey) && notBlank(llmModel);
        }

        public boolean ttsConfigured() {
            return notBlank(apiKey) && notBlank(ttsModel) && notBlank(ttsVoice);
        }

        public boolean imageConfigured() {
            return notBlank(apiKey) && notBlank(imageModel);
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
            String voiceResearchCleanupToken) {
        public boolean configured() {
            return url != null && !url.isBlank()
                    && serviceRoleKey != null && !serviceRoleKey.isBlank();
        }
    }
}
