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
 * An in-memory, request-hot cache of every registered story's content, loaded from Postgres.
 * Reloaded once on boot and again after every POST /v1/admin/stories/import (see
 * StoryImportService) rather than per-request - this data changes by an explicit import or by
 * calling {@link #reload()}, not every request.
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

    /** Public request payloads (e.g. voice-research uploads) address stories by slug, not the internal id. */
    public StoryManifest getBySlug(String slug) {
        return registryBySlug.get(slug);
    }

    public StoryManifest getDefaultBetaStory() {
        return registry.get(DEFAULT_BETA_STORY_ID);
    }
}
