package com.qstory.backend.voicecast.service;

import com.qstory.backend.story.CastEntry;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.story.service.StoryRegistry;
import org.springframework.stereotype.Service;

/** voice-cast.mjs를 Java로 이식한 버전. */
@Service
public class VoiceCastService {

    private final StoryRegistry registry;

    public VoiceCastService(StoryRegistry registry) {
        this.registry = registry;
    }

    public CastEntry voiceCastForSpeaker(String storyId, String speaker) {
        StoryManifest story = registry.get(storyId);
        if (story == null) {
            return null;
        }
        return story.cast().entrySet().stream()
                .filter(entry -> entry.getKey().equals(speaker) || entry.getValue().speakerId().equals(speaker))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 실시간 음성(그레텔 답·도움·선택지 미리 만들기·이름이 들어간 대사, 관리자 나레이션 다시 만들기)의 TTS 입력.
     *
     * <p>대사만 보낸다. 예전에는 캐릭터 설명(profile)·연기 지시(direction)와 "Read the following Korean line aloud..."
     * 문장을 앞에 붙였다. 3.1 preview는 이걸 지시로만 썼지만 3.8(#123, 2026-11-21)은 영어 문장을 그대로 소리 내어
     * 읽는다(2026-11-25 데모에서 확인, 받아 적기로 재현). 목소리 자체는 cast.voice()로 정해지므로 대사만 보내도
     * 캐릭터마다 다른 목소리는 그대로다. 말투를 더 주고 싶으면 "[warmly] 대사"처럼 한두 단어 대괄호 태그만 쓴다.
     * 문장형 지시는 다시 읽힌다.
     */
    public String buildGeminiTtsPerformanceInput(String storyId, String speaker, String text) {
        CastEntry cast = voiceCastForSpeaker(storyId, speaker);
        if (cast == null) {
            throw new IllegalStateException("Unknown cast speaker: " + storyId + "/" + speaker);
        }
        return performanceInput(cast, text);
    }

    static String performanceInput(CastEntry cast, String text) {
        return text.trim();
    }
}
