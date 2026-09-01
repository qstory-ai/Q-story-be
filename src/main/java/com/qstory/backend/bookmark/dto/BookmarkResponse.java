package com.qstory.backend.bookmark.dto;

import com.qstory.backend.bookmark.entity.Bookmark;
import java.time.Instant;
import java.util.UUID;

public record BookmarkResponse(UUID id, String storyId, Instant createdAt) {

    public static BookmarkResponse of(Bookmark bookmark) {
        return new BookmarkResponse(bookmark.getId(), bookmark.getStoryId(), bookmark.getCreatedAt());
    }
}
