package com.qstory.backend.choicecopy.service;
import com.qstory.backend.choicecopy.ChoiceCopyVariant;
import com.qstory.backend.choicecopy.repository.ChoiceCopyRepository;

import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * In-memory cache of the reviewer-approved THREE_PATHS copy bank, loaded from Postgres once on
 * boot and again after every POST /v1/admin/stories/import (see StoryImportService) - this is
 * read on every routed question, so it is never queried per-request.
 */
@Component
@Order(1)
public class ChoiceCopyRegistry implements ApplicationRunner {

    private final ChoiceCopyRepository repository;

    private volatile Map<String, List<ChoiceCopyVariant>> copyBank = Map.of();

    public ChoiceCopyRegistry(ChoiceCopyRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    public void reload() {
        copyBank = repository.groupedByFamily();
    }

    public List<ChoiceCopyVariant> variantsFor(String familyId) {
        return copyBank.getOrDefault(familyId, List.of());
    }
}
