package com.qstory.backend.story.service;
import com.qstory.backend.story.repository.StoryContentRepository;
import com.qstory.backend.story.StoryManifest;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Postgres에서 로드한, 등록된 모든 스토리의 콘텐츠를 메모리에 올려두는, 요청마다 즉시 사용되는 캐시다.
 * 요청마다가 아니라 부팅 시 한 번, 그리고 POST /v1/admin/stories/import(StoryImportService 참고)가
 * 있을 때마다 다시 로드된다 - 이 데이터는 명시적인 임포트나 {@link #reload()} 호출에 의해서만 바뀐다.
 */
@Component
@Order(1)
public class StoryRegistry implements ApplicationRunner {

    public static final String DEFAULT_BETA_STORY_ID = "HG";

    private final StoryContentRepository contentRepository;

    private volatile Map<String, StoryManifest> registry = Map.of();
    private volatile Map<String, StoryManifest> registryBySlug = Map.of();

    public StoryRegistry(StoryContentRepository contentRepository) {
        this.contentRepository = contentRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    public void reload() {
        Map<String, StoryManifest> loaded = new LinkedHashMap<>();
        Map<String, StoryManifest> loadedBySlug = new LinkedHashMap<>();
        for (StoryManifest story : contentRepository.loadAll()) {
            loaded.put(story.storyId(), story);
            loadedBySlug.put(story.slug(), story);
        }
        registry = Map.copyOf(loaded);
        registryBySlug = Map.copyOf(loadedBySlug);
    }

    public StoryManifest get(String storyId) {
        return registry.get(storyId);
    }

    public Collection<StoryManifest> all() {
        return registry.values();
    }

    /** 공개 요청 페이로드(예: voice-research 업로드)는 내부 id가 아니라 slug로 스토리를 지정한다. */
    public StoryManifest getBySlug(String slug) {
        return registryBySlug.get(slug);
    }

    public StoryManifest getDefaultBetaStory() {
        return registry.get(DEFAULT_BETA_STORY_ID);
    }
}
