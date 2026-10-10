package com.qstory.backend.voicecast.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.qstory.backend.story.CastEntry;
import org.junit.jupiter.api.Test;

/** 실시간 음성 입력이 미리 녹음한 그레텔 대사와 같은 형식인지 고정한다(목소리가 달라 들리던 원인). */
class VoiceCastServiceTest {

    @Test
    void liveSpeechUsesTheSameInputShapeAsTheRecordedLines() {
        CastEntry gretel = new CastEntry(
                "HG-SPK-GRETEL", "character", "그레텔", "Leda",
                "A perceptive young girl who speaks directly and kindly to the child listening.",
                "Sound youthful, curious, and warm in questions; calm and decisive during the escape.", null);

        assertThat(VoiceCastService.performanceInput(gretel, " 너는 어떤 방법이 있을 것 같아? "))
                .isEqualTo("A perceptive young girl who speaks directly and kindly to the child listening. "
                        + "Sound youthful, curious, and warm in questions; calm and decisive during the escape.\n"
                        + "Read the following Korean line aloud exactly as written, in natural Korean, and say nothing else:\n"
                        + "너는 어떤 방법이 있을 것 같아?");
    }
}
