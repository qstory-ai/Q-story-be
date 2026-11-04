package com.qstory.backend.provider.openrouter.util;

import java.util.List;
import org.springframework.stereotype.Component;

/** LLM이 돌려준 한국어 답에서 자주 틀리는 표기를 바로잡는다. 아이에게 보이기 전에 한 번 거친다. */
@Component
public class ResponseTextNormalizer {

    private static final List<String[]> RESPONSE_TEXT_CORRECTIONS = List.of(
            new String[] {"다가지지", "다가가지"},
            new String[] {"살쳐보", "살펴보"},
            new String[] {"날개를 날개짓하", "날갯짓하"});

    public String normalizeKoreanResponseText(String value) {
        String text = value;
        for (String[] correction : RESPONSE_TEXT_CORRECTIONS) {
            text = text.replace(correction[0], correction[1]);
        }
        return text;
    }
}
