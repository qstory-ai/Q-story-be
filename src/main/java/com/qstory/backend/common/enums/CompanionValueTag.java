package com.qstory.backend.common.enums;

import java.util.Arrays;
import java.util.List;

/** v1 잠정 분류 체계 - 이것이 공유하는 유의사항은 CompanionTopicTag의 클래스 문서를 참조. */
public enum CompanionValueTag {
    EMPATHY_CARE("배려·공감"),
    COURAGE("용기"),
    HONESTY("정직"),
    COOPERATION("협동"),
    RESPONSIBILITY("책임감"),
    CURIOSITY_INQUIRY("호기심탐구"),
    SELF_REGULATION("자기조절"),
    FAIRNESS("공정함"),
    SAFETY_AWARENESS("안전인식"),
    CREATIVITY("창의성");

    private final String label;

    CompanionValueTag(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static final List<String> ALL_LABELS =
            Arrays.stream(values()).map(CompanionValueTag::label).toList();

    public static CompanionValueTag fromLabel(String label) {
        return Arrays.stream(values()).filter(tag -> tag.label.equals(label)).findFirst().orElse(null);
    }
}
