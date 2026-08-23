package com.qstory.backend.identity;

/** Kept as a real enum, unlike StoryAvailability/CastRole/TrafficType: authorization code branches on this. */
public enum Role {
    DIRECTOR,
    CLASS_ACCOUNT,
    PARENT
}
