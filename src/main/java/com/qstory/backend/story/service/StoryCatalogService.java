package com.qstory.backend.story.service;
import com.qstory.backend.story.StoryManifest;

import com.qstory.backend.common.enums.StoryAvailability;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.entitlement.service.EntitlementService;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.story.dto.StoryCatalogEntry;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Public listing/lookup over StoryRegistry, trimmed to catalog metadata (see StoryCatalogEntry). */
@Service
public class StoryCatalogService {

    private final StoryRegistry registry;
    private final EntitlementService entitlementService;

    public StoryCatalogService(StoryRegistry registry, EntitlementService entitlementService) {
        this.registry = registry;
        this.entitlementService = entitlementService;
    }

    public List<StoryCatalogEntry> list() {
        return registry.all().stream()
                .filter(story -> !StoryAvailability.RETIRED.equals(story.availability()))
                .map(StoryCatalogService::toEntry)
                .sorted(Comparator.comparing(StoryCatalogEntry::storyId))
                .toList();
    }

    /** callerOrNull is null for an anonymous request - required so the free demo stays reachable with no token. */
    public StoryCatalogEntry get(String storyId, CurrentUser callerOrNull) {
        StoryManifest story = registry.get(storyId);
        if (story == null || StoryAvailability.RETIRED.equals(story.availability())) {
            throw ApiException.contractError(ErrorCode.STORY_NOT_REGISTERED, "요청한 작품이 등록되어 있지 않아요.");
        }
        entitlementService.assertAccessible(story, callerOrNull);
        return toEntry(story);
    }

    private static StoryCatalogEntry toEntry(StoryManifest story) {
        return new StoryCatalogEntry(
                story.storyId(), story.slug(), story.title(), story.availability(),
                story.contentVersion(), story.castVersion());
    }
}
