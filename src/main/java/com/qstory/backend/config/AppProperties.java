package com.qstory.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qstory")
public record AppProperties(
        List<String> allowedOrigins,
        /**
         * 태블릿·폰 네이티브 앱(fe/q-story-web을 Capacitor로 감싼 것)의 WebView 출처. allowedOrigins와
         * 분리한 이유: ALLOWED_ORIGINS 환경변수가 allowedOrigins를 통째로 덮어쓰므로, 거기에 끼워 넣으면
         * 배포 환경변수를 손으로 맞춰야 한다. 이 목록은 application.yml에서만 정하고 SecurityConfig가 allowedOrigins에 더한다.
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
        ConversationRecord conversationRecord,
        VoiceStreaming voiceStreaming,
        VoiceLoudness voiceLoudness,
        Finops finops) {

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 대화 원장(conversation_record) 보존 일수. 0 이하면 만료 삭제를 하지 않는다. */
    public record ConversationRecord(int retentionDays) {}

    /**
     * 음성 스트리밍 스위치(소리 품질·한도에 문제가 생기면 Railway 변수로 바로 끈다).
     * tts: POST /v1/narrations/stream이 Gemini가 만드는 대로 PCM을 내보낸다. companionReply: POST /v1/companion-chat/messages가
     * stream:true 요청에 답 문장을 먼저 한 줄로 보낸다(NDJSON).
     */
    public record VoiceStreaming(boolean tts, boolean companionReply) {}

    /** 실시간 TTS 음량을 미리 녹음한 고정 음성(-17 LUFS)에 맞출지와 그 목표. */
    public record VoiceLoudness(boolean enabled, double targetLufs) {
        /** 맞출 음량 - 끄면 null(Gemini가 준 그대로). */
        public Double targetOrNull() {
            return enabled ? targetLufs : null;
        }
    }

    /** 그라파나 비용 대시보드용 외부 청구 조회 토큰. */
    public record Finops(String railwayApiToken) {}

    public record Payments(Toss toss) {}

    public record Toss(String secretKey, int parentMonthlyAmount, int organizationStudentMonthlyAmount, int accessDays) {
        public boolean configured() {
            return notBlank(secretKey);
        }
    }

    /** 운영자 전용 엔드포인트(/v1/admin/*, 동화 가져오기, 디스코드 수동 실행 등)를 지키는 공유 비밀값(X-Admin-Token). */
    public record Admin(String token) {
        public boolean tokenConfigured() {
            return notBlank(token);
        }
    }

    /**
     * rememberMeTtlDays는 "로그인 유지"를 켠 로그인(과 모든 회원가입)의 토큰 수명, sessionTtlHours는 끈 로그인의
     * 토큰 수명이다. 어느 쪽이든 POST /v1/auth/refresh로 같은 모드의 새 토큰을 받아 수명을 늘린다.
     */
    public record Auth(String jwtSecret, long rememberMeTtlDays, long sessionTtlHours) {
        public boolean configured() {
            return notBlank(jwtSecret);
        }
    }

    public record Providers(Rtzr rtzr, OpenRouter openRouter, Gemini gemini, Oauth oauth) {}

    public record Rtzr(String clientId, String clientSecret) {
        public boolean configured() {
            return notBlank(clientId) && notBlank(clientSecret);
        }
    }

    public record Oauth(Google google, Kakao kakao) {}

    public record Google(String clientId) {
        public boolean configured() {
            return notBlank(clientId);
        }
    }

    public record Kakao(String appId) {
        public boolean configured() {
            return notBlank(appId);
        }
    }

    /**
     * @param llmFallbackModels 텍스트(JSON 스키마) 호출의 대체 모델들 - 기본 모델이 실패(한도 초과·장애)하면
     *                          OpenRouter가 이 순서대로 넘겨 받는다. 비어 있으면 기본 모델만 쓴다. 이미지 모델에는 쓰지 않는다.
     */
    public record OpenRouter(
            String apiKey, String llmModel, String imageModel, List<String> llmFallbackModels) {
        public OpenRouter {
            llmFallbackModels = llmFallbackModels == null ? List.of() : llmFallbackModels.stream()
                    .filter(model -> model != null && !model.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        }

        public boolean llmConfigured() {
            return notBlank(apiKey) && notBlank(llmModel);
        }

        public boolean imageConfigured() {
            return notBlank(apiKey) && notBlank(imageModel);
        }
    }

    /** TTS 전용 - OpenRouter 카탈로그에 없는 Gemini 자체 모델/목소리를 쓰므로 Gemini API를 직접 호출한다. */
    public record Gemini(String apiKey, String ttsModel, String ttsVoice, String prefetchApiKey) {
        public boolean ttsConfigured() {
            return notBlank(apiKey) && notBlank(ttsModel) && notBlank(ttsVoice);
        }

        /** 선택지 음성 미리 만들기(prefetch) 전용 키 - 비어 있으면 prefetch 요청은 409 PREFETCH_DISABLED. */
        public boolean prefetchConfigured() {
            return notBlank(prefetchApiKey) && notBlank(ttsModel) && notBlank(ttsVoice);
        }
    }

    public record Supabase(
            String url,
            String serviceRoleKey,
            String voiceResearchBucket,
            String storyAudioBucket,
            String storyImageBucket,
            String profileImageBucket) {
        public boolean configured() {
            return notBlank(url) && notBlank(serviceRoleKey);
        }
    }
}
