package com.qstory.backend.bookmark.controller;

import com.qstory.backend.bookmark.dto.BookmarkResponse;
import com.qstory.backend.bookmark.dto.CreateBookmarkRequest;
import com.qstory.backend.bookmark.service.BookmarkService;
import com.qstory.backend.identity.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bookmarks", description = "Saved stories (부모/선생님 공용)")
@RestController
@RequestMapping("/v1/me/bookmarks")
public class BookmarkController {

    private final BookmarkService service;
    private final CurrentUserResolver currentUserResolver;

    public BookmarkController(BookmarkService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "List my bookmarks", description = "Any authenticated role. Most recent first.")
    @GetMapping
    public List<BookmarkResponse> list() {
        return service.listMine(currentUserResolver.require());
    }

    @Operation(summary = "Save a story", description = "Idempotent - saving an already-saved story returns the existing bookmark.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookmarkResponse create(@RequestBody CreateBookmarkRequest request) {
        return service.create(currentUserResolver.require(), request);
    }

    @Operation(summary = "Remove a saved story", description = "Idempotent - deleting a non-existent bookmark succeeds silently.")
    @DeleteMapping("/{storyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String storyId) {
        service.delete(currentUserResolver.require(), storyId);
    }
}
