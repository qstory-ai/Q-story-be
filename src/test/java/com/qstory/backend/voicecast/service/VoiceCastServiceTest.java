package com.qstory.backend.voicecast.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.qstory.backend.story.CastEntry;
import org.junit.jupiter.api.Test;

/** 실시간 음성 입력에 영어 지시문이 섞이지 않는지 고정한다. 3.8 TTS는 문장형 지시를 소리 내어 읽는다. */
class VoiceCastServiceTest {

    @Test
    void liveSpeechSendsOnlyTheLineSoDirectionsAreNotReadAloud() {
        CastEntry gretel = new CastEntry(
                "HG-SPK-GRETEL", "character", "그레텔", "Leda",
                "A perceptive young girl who speaks directly and kindly to the child listening.",
                "Sound youthful, curious, and warm in questions; calm and decisive during the escape.", null);

        assertThat(VoiceCastService.performanceInput(gretel, " 너는 어떤 방법이 있을 것 같아? "))
                .isEqualTo("너는 어떤 방법이 있을 것 같아?");
    }
}
