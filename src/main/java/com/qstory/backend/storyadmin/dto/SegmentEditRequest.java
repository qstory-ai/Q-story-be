package com.qstory.backend.storyadmin.dto;

/**
 * An edit to one segment's payload.
 *
 * <p>{@code payload} replaces the whole map rather than merging: a merge cannot express removing a
 * key, and segment payloads differ by kind, so a partial update would quietly keep fields that no
 * longer belong to the shape being written.
 */
public record SegmentEditRequest(
        Integer baseRevision, java.util.Map<String, Object> payload, String summary) {}
