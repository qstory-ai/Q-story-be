package com.qstory.backend.storyadmin.dto;

import com.qstory.backend.story.entity.StorySegment;
import java.util.Map;

/** A segment as an editor sees it - no lazy associations, so it doubles as a revision snapshot. */
public record SegmentView(
        String id,
        String sceneId,
        int displayOrder,
        String kind,
        boolean branchPoint,
        boolean narrationStale,
        Map<String, Object> payload) {

    public static SegmentView of(StorySegment segment) {
        return new SegmentView(
                segment.getId().toString(),
                segment.getScene().getId(),
                segment.getDisplayOrder(),
                segment.getKind(),
                segment.isBranchPoint(),
                // Derived, not stored: the audio is stale exactly when the line no longer matches
                // what was recorded.
                segment.getNarrationText() != null
                        && !segment.getNarrationText().equals(segment.getPayload().get("text")),
                segment.getPayload());
    }
}
