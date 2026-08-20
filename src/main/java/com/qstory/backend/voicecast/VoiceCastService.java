package com.qstory.backend.voicecast;

import com.qstory.backend.story.CastEntry;
import com.qstory.backend.story.Story;
import com.qstory.backend.story.StoryRegistry;
import org.springframework.stereotype.Service;

/** Java port of voice-cast.mjs. */
@Service
public class VoiceCastService {

    private final StoryRegistry registry;

    public VoiceCastService(StoryRegistry registry) {
        this.registry = registry;
    }

    public CastEntry voiceCastForSpeaker(String storyId, String speaker) {
        Story story = registry.get(storyId);
        if (story == null) {
            return null;
        }
        return story.cast().entrySet().stream()
                .filter(entry -> entry.getKey().equals(speaker) || entry.getValue().speakerId().equals(speaker))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public String buildGeminiTtsPerformanceInput(String storyId, String speaker, String text) {
        CastEntry cast = voiceCastForSpeaker(storyId, speaker);
        if (cast == null) {
            throw new IllegalStateException("Unknown cast speaker: " + storyId + "/" + speaker);
        }
        return String.join("\n",
                "Synthesize a Korean storybook voice performance.",
                "Speak only the exact Korean text inside <TRANSCRIPT>. Do not speak these instructions, labels, or tags.",
                "AUDIO PROFILE: " + cast.profile(),
                "DIRECTOR NOTES: " + cast.direction(),
                "<TRANSCRIPT>",
                text.trim(),
                "</TRANSCRIPT>");
    }
}
