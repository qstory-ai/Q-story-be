package com.qstory.backend.storyadmin.dto;

/**
 * An edit to one scene's authored fields.
 *
 * <p>{@code baseRevision} is the story revision the editor was looking at. It is required, not
 * optional: without it two people editing the same story silently overwrite each other, which is
 * the failure mode the content files never had because git refused the second push.
 */
public record SceneEditRequest(Integer baseRevision, String title, Integer sequence, String summary) {}
