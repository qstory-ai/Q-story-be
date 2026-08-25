package com.qstory.backend.story.dto;

/**
 * 스토리의 공개 카탈로그 뷰 - 메타데이터만 담는다. anchors/action families/cast는 절대 담지 않는다:
 * 이들은 question/narration 파이프라인(StoryRegistryService 참고)에 의해 (storyId, anchorId,
 * sceneId) 단위로 점진적으로 해석되며, 처음부터 전부 노출되지 않는다.
 */
public record StoryCatalogEntry(
        String storyId, String slug, String title, String availability, String contentVersion, String castVersion,
        String coverImageUrl, String description, String category, boolean requiresEntitlement) {}
