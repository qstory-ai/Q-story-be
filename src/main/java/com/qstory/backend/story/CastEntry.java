package com.qstory.backend.story;

public record CastEntry(
        String speakerId,
        String role,
        String displayName,
        String voice,
        String profile,
        String direction,
        String samePersonKey) {}
