package com.qstory.backend.storyadmin.dto;

import com.qstory.backend.story.entity.StoryScene;

/** A scene as an editor sees it - no lazy associations, so it is also what a revision snapshots. */
public record SceneView(String id, String title, int sequence, String checkpointId) {

    public static SceneView of(StoryScene scene) {
        return new SceneView(scene.getId(), scene.getTitle(), scene.getSequence(), scene.getCheckpointId());
    }
}
