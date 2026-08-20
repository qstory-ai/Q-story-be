package com.qstory.backend.common.enums;

import java.util.Map;

/** The 13 beta funnel/telemetry events, each pinned to the source that is allowed to emit it. */
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
