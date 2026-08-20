package com.qstory.backend.story;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.story.dto.StoryCatalogEntry;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Public listing/lookup over StoryRegistry, trimmed to catalog metadata (see StoryCatalogEntry). */
@Service
public class StoryCatalogService {

    private static final String RETIRED = "RETIRED";

    private final StoryRegistry registry;

    public StoryCatalogService(StoryRegistry registry) {
        this.registry = registry;
    }

    public List<StoryCatalogEntry> list() {
        return registry.all().stream()
                .filter(story -> !RETIRED.equals(story.availability()))
                .map(StoryCatalogService::toEntry)
                .sorted(Comparator.comparing(StoryCatalogEntry::storyId))
                .toList();
    }

    public StoryCatalogEntry get(String storyId) {
        Story story = registry.get(storyId);
        if (story == null || RETIRED.equals(story.availability())) {
            throw ApiException.contractError(ErrorCode.STORY_NOT_REGISTERED, "요청한 작품이 등록되어 있지 않아요.");
        }
        return toEntry(story);
    }

    private static StoryCatalogEntry toEntry(Story story) {
        return new StoryCatalogEntry(
                story.storyId(), story.slug(), story.title(), story.availability(),
                story.contentVersion(), story.castVersion());
    }
}
