package com.qstory.backend.bookmark.service;

import com.qstory.backend.bookmark.dto.BookmarkResponse;
import com.qstory.backend.bookmark.dto.CreateBookmarkRequest;
import com.qstory.backend.bookmark.entity.Bookmark;
import com.qstory.backend.bookmark.repository.BookmarkRepository;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자의 "저장한 작품" CRUD. 저장은 (user_id, story_id) 조합으로 최대 1건이라, 이미 저장돼
 * 있을 때 create를 다시 호출해도 새 행을 만들지 않고 기존 것을 그대로 반환한다(idempotent).
 * 삭제는 존재하지 않는 조합에 대해 조용히 성공한다 - 클라이언트가 두 번 눌러도 오류가 나지 않게.
 */
@Service
public class BookmarkService {

    private static final int MAX_STORY_ID_LENGTH = 64;

    private final BookmarkRepository bookmarkRepository;
    private final AppUserRepository userRepository;

    public BookmarkService(BookmarkRepository bookmarkRepository, AppUserRepository userRepository) {
        this.bookmarkRepository = bookmarkRepository;
        this.userRepository = userRepository;
    }

    public List<BookmarkResponse> listMine(CurrentUser caller) {
        return bookmarkRepository.findByUser_IdOrderByCreatedAtDesc(caller.userId()).stream()
                .map(BookmarkResponse::of)
                .toList();
    }

    @Transactional
    public BookmarkResponse create(CurrentUser caller, CreateBookmarkRequest request) {
        String storyId = normalizeStoryId(request.storyId());
        return bookmarkRepository.findByUser_IdAndStoryId(caller.userId(), storyId)
                .map(BookmarkResponse::of)
                .orElseGet(() -> {
                    AppUser user = userRepository.getReferenceById(caller.userId());
                    Bookmark saved = bookmarkRepository.save(Bookmark.builder()
                            .user(user)
                            .storyId(storyId)
                            .createdAt(Instant.now())
                            .build());
                    return BookmarkResponse.of(saved);
                });
    }

    @Transactional
    public void delete(CurrentUser caller, String storyId) {
        bookmarkRepository.findByUser_IdAndStoryId(caller.userId(), normalizeStoryId(storyId))
                .ifPresent(bookmarkRepository::delete);
    }

    private static String normalizeStoryId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "storyId가 필요해요.");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_STORY_ID_LENGTH) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "storyId 길이가 너무 길어요.");
        }
        return trimmed;
    }
}
