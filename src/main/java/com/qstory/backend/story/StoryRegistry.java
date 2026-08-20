package com.qstory.backend.story;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * An in-memory, request-hot cache of every registered story's content, loaded from Postgres.
 * Reloaded once on boot (after {@link StoryContentSeeder}, via @Order) rather than per-request -
 * this data changes by editing rows and restarting/calling {@link #reload()}, not every request.
 */
@Component
@Order(1)
public class StoryRegistry implements ApplicationRunner {

    public static final String DEFAULT_BETA_STORY_ID = "HG";

    private final StoryContentRepository contentRepository;

    private volatile Map<String, Story> registry = Map.of();
    private volatile Map<String, Story> registryBySlug = Map.of();

    public StoryRegistry(StoryContentRepository contentRepository) {
        this.contentRepository = contentRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    public void reload() {
        Map<String, Story> loaded = new LinkedHashMap<>();
        Map<String, Story> loadedBySlug = new LinkedHashMap<>();
        for (Story story : contentRepository.loadAll()) {
            loaded.put(story.storyId(), story);
            loadedBySlug.put(story.slug(), story);
        }
        registry = Map.copyOf(loaded);
        registryBySlug = Map.copyOf(loadedBySlug);
    }

    public Story get(String storyId) {
        return registry.get(storyId);
    }

    public Collection<Story> all() {
        return registry.values();
    }

    /** Public request payloads (e.g. voice-research uploads) address stories by slug, not the internal id. */
    public Story getBySlug(String slug) {
        return registryBySlug.get(slug);
    }

    public Story getDefaultBetaStory() {
        return registry.get(DEFAULT_BETA_STORY_ID);
    }
}
