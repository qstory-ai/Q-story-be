package com.qstory.backend.bookmark.repository;

import com.qstory.backend.bookmark.entity.Bookmark;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookmarkRepository extends JpaRepository<Bookmark, UUID> {

    List<Bookmark> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    Optional<Bookmark> findByUser_IdAndStoryId(UUID userId, String storyId);
}
