package com.qstory.backend.storyadmin.dto;

import com.qstory.backend.story.entity.StorySegment;
import java.util.Map;

/** 편집자가 보는 그대로의 segment - 지연 로딩 연관관계가 없어서 리비전 스냅샷으로도 그대로 쓸 수 있다. */
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
                // 저장된 값이 아니라 계산된 값이다: 대사가 녹음된 내용과 더 이상 일치하지 않을 때
                // 정확히 오디오가 stale 상태가 된다.
                segment.getNarrationText() != null
                        && !segment.getNarrationText().equals(segment.getPayload().get("text")),
                segment.getPayload());
    }
}
