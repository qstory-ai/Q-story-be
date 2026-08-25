package com.qstory.backend.common.enums;

import java.util.Arrays;
import java.util.List;

/** v1 잠정 분류 체계 - 이것이 공유하는 유의사항은 CompanionTopicTag의 클래스 문서를 참조. */
public enum CompanionToneTag {
    CURIOSITY("호기심"),
    JOY("즐거움"),
    WORRY_ANXIETY("걱정·불안"),
    SADNESS("속상함·슬픔"),
    ANGER("화남"),
    SURPRISE("놀람"),
    CONFIDENCE("자신감"),
    NEUTRAL("무덤덤");

    private final String label;

    CompanionToneTag(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static final List<String> ALL_LABELS =
            Arrays.stream(values()).map(CompanionToneTag::label).toList();

    public static CompanionToneTag fromLabel(String label) {
        return Arrays.stream(values()).filter(tag -> tag.label.equals(label)).findFirst().orElse(null);
    }
}
