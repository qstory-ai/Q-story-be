package com.qstory.backend.common.enums;

import java.util.List;

/**
 * A story's publish-status tag. Plain string values rather than a Java enum: nothing in the
 * codebase branches on a fixed compile-time set of these (both consumers below already re-typed
 * the value set as string literals instead of importing an enum), and ops should be able to add a
 * new lifecycle value without a backend redeploy.
 */
public final class StoryAvailability {

    public static final String INTERNAL = "INTERNAL";
    public static final String BETA = "BETA";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String RETIRED = "RETIRED";

    /** Every currently-known value - used to validate a value at write time. */
    public static final List<String> VALUES = List.of(INTERNAL, BETA, PUBLISHED, RETIRED);

    /** Values that allow the question/narration pipelines to run against this story (see StoryRegistryService). */
    public static final List<String> ACTIVE = List.of(INTERNAL, BETA, PUBLISHED);

    private StoryAvailability() {}
}
