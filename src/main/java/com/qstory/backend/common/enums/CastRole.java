package com.qstory.backend.common.enums;

import java.util.List;

/**
 * A voice-cast entry's role tag. Plain string rather than a Java enum: nothing branches on it,
 * it's copied straight through to the client (see StoryContentRepository/CastEntry).
 */
public final class CastRole {

    public static final String NARRATOR = "NARRATOR";
    public static final String CHARACTER = "CHARACTER";

    public static final List<String> VALUES = List.of(NARRATOR, CHARACTER);

    private CastRole() {}
}
