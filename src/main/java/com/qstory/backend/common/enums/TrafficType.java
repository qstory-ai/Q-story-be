package com.qstory.backend.common.enums;

import java.util.List;

/**
 * A funnel session's traffic-segmentation tag. Plain string rather than a Java enum: it has no
 * branching logic beyond the UNKNOWN default, and is a simple, growable segmentation vocabulary.
 */
public final class TrafficType {

    public static final String UNKNOWN = "UNKNOWN";
    public static final String BETA = "BETA";
    public static final String QA = "QA";
    public static final String DEV = "DEV";

    public static final List<String> VALUES = List.of(UNKNOWN, BETA, QA, DEV);

    private TrafficType() {}
}
