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
     * 실시간 음성(그레텔 답·도움·선택지 미리 만들기·이름이 들어간 대사)의 TTS 입력. 미리 녹음한 고정 음성
     * (fe assets/.../fixed-narration-metadata.json, Q-30·Q-31)을 만들 때와 같은 문장 형식이다 - 형식이 다르면
     * 같은 목소리(voice)·모델이어도 말투가 달라져, 녹음한 대사와 실시간 답이 다른 사람처럼 들렸다.
     */
    public String buildGeminiTtsPerformanceInput(String storyId, String speaker, String text) {
        CastEntry cast = voiceCastForSpeaker(storyId, speaker);
        if (cast == null) {
            throw new IllegalStateException("Unknown cast speaker: " + storyId + "/" + speaker);
        }
        return performanceInput(cast, text);
    }

    static String performanceInput(CastEntry cast, String text) {
        return cast.profile() + " " + cast.direction() + "\n"
                + "Read the following Korean line aloud exactly as written, in natural Korean, and say nothing else:\n"
                + text.trim();
    }
}
