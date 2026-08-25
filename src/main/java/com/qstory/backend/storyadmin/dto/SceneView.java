package com.qstory.backend.storyadmin.dto;

import com.qstory.backend.story.entity.StoryScene;

/** 편집자가 보는 그대로의 scene - 지연 로딩 연관관계가 없어서 리비전이 스냅샷으로 담는 것도 바로 이 형태다. */
public record SceneView(String id, String title, int sequence, String checkpointId) {

    public static SceneView of(StoryScene scene) {
        return new SceneView(scene.getId(), scene.getTitle(), scene.getSequence(), scene.getCheckpointId());
    }
}
