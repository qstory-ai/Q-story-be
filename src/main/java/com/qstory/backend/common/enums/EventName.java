package com.qstory.backend.common.enums;

import java.util.Map;

/** 13개의 베타 퍼널/텔레메트리 이벤트로, 각각이 발생시킬 수 있는 소스에 고정되어 있다. */
public enum EventName {
    LANDING_VIEW(EventSource.LANDING),
    LANDING_CTA_CLICK(EventSource.LANDING),
    STORY_STARTED(EventSource.PLAYER),
    SCENE_REACHED(EventSource.PLAYER),
    QUESTION_INVITE_SHOWN(EventSource.PLAYER),
    QUESTION_SKIPPED(EventSource.PLAYER),
    QUESTION_STARTED(EventSource.PLAYER),
    CHOICE_SELECTED(EventSource.PLAYER),
    QUESTION_RESULT(EventSource.PLAYER),
    PLAYBACK_ISSUE(EventSource.PLAYER),
    EXPLICIT_EXIT(EventSource.PLAYER),
    STORY_COMPLETED(EventSource.PLAYER),
    PARENT_REPORT_OPENED(EventSource.PLAYER),
    SURVEY_OPENED(EventSource.PLAYER);

    private final EventSource requiredSource;

    EventName(EventSource requiredSource) {
        this.requiredSource = requiredSource;
    }

    public EventSource requiredSource() {
        return requiredSource;
    }

    private static final Map<EventName, EventSource> SOURCES = java.util.stream.Stream.of(values())
            .collect(java.util.stream.Collectors.toMap(name -> name, EventName::requiredSource));

    public static Map<EventName, EventSource> sources() {
        return SOURCES;
    }
}
