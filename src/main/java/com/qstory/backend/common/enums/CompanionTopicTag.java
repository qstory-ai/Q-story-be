package com.qstory.backend.common.enums;

import java.util.Arrays;
import java.util.List;

/**
 * v1 잠정 분류 체계 - 아동발달 전문가의 검토 없이 작성됨(제품 책임자가 전문가 검토 없이 진행하는 것을
 * 명시적으로 승인함). LLM 스키마 enum이자 후속(downstream) 개인화 신호로 계속 사용 가능하도록 의도적으로
 * 작고 닫힌 집합으로 유지함; 전문가 의견을 받을 수 있게 되면 다시 검토할 것.
 */
public enum CompanionTopicTag {
    ANIMALS("동물"),
    NATURE_WEATHER("자연·날씨"),
    FOOD("음식"),
    FAMILY("가족"),
    FRIENDS("친구·또래관계"),
    EMOTIONS("감정"),
    SAFETY_DANGER("안전·위험"),
    IMAGINATION_FANTASY("상상·판타지"),
    BODY_HEALTH("몸·건강"),
    RULES_RIGHT_WRONG("규칙·옳고그름"),
    STORY_EVENTS("이야기속사건"),
    OTHER("기타");

    private final String label;

    CompanionTopicTag(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static final List<String> ALL_LABELS =
            Arrays.stream(values()).map(CompanionTopicTag::label).toList();

    public static CompanionTopicTag fromLabel(String label) {
        return Arrays.stream(values()).filter(tag -> tag.label.equals(label)).findFirst().orElse(null);
    }
}
