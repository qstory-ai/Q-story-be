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

/** StoryRegistry에 대한 공개 목록 조회/단건 조회로, 카탈로그 메타데이터로 축소된 형태다 (StoryCatalogEntry 참고). */
@Service
public class StoryCatalogService {

    private final StoryRegistry registry;
    private final EntitlementService entitlementService;

    public StoryCatalogService(StoryRegistry registry, EntitlementService entitlementService) {
        this.registry = registry;
        this.entitlementService = entitlementService;
    }

    /**
     * 알려진 한계: {@link #get}과 달리 이 메서드는 {@link EntitlementService}를 호출하지 않는다 - RETIRED가
     * 아닌 모든 스토리의 메타데이터는 entitlement 여부와 무관하게 익명 호출자에게도 노출된다. 지금은
     * 문제없지만(HG는 무료다), entitlement로 제한되는 스토리가 하나라도 생기면 {@link #get}에서는 402를
     * 받게 될 호출자에게 title/cover/description이 유출된다. 그런 상황이 오기 전에 entitlement 기준으로
     * 필터링하거나 표시를 추가해야 한다.
     */
    public List<StoryCatalogEntry> list() {
        return registry.all().stream()
                .filter(story -> !StoryAvailability.RETIRED.equals(story.availability()))
                .map(StoryCatalogService::toEntry)
                .sorted(Comparator.comparing(StoryCatalogEntry::storyId))
                .toList();
    }

    /** callerOrNull은 익명 요청일 때 null이다 - 토큰 없이도 무료 데모에 접근할 수 있도록 하기 위해 필요하다. */
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
                story.contentVersion(), story.castVersion(),
                story.coverImageUrl(), story.description(), story.category(),
                story.requiresEntitlement());
    }
}
