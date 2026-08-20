package com.qstory.backend.story.dto;

/**
 * Public catalog view of a story - metadata only. Never carries anchors/action families/cast:
 * those are resolved progressively per (storyId, anchorId, sceneId) by the question/narration
 * pipelines (see StoryRegistryService), not exposed wholesale up front.
 */
public record StoryCatalogEntry(
        String storyId, String slug, String title, String availability, String contentVersion, String castVersion) {}
