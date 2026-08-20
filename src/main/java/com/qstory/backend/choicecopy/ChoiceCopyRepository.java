package com.qstory.backend.choicecopy;

import com.qstory.backend.persistence.entity.ChoiceCopyVariantEntity;
import com.qstory.backend.persistence.entity.StoryActionFamilyEntity;
import com.qstory.backend.persistence.repository.ChoiceCopyVariantEntityRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Wraps ChoiceCopyVariantEntityRepository: entity->domain assembly for ChoiceCopyRegistry, plus save helpers for StoryContentSeeder. */
@Component
public class ChoiceCopyRepository {

    private final ChoiceCopyVariantEntityRepository repository;

    public ChoiceCopyRepository(ChoiceCopyVariantEntityRepository repository) {
        this.repository = repository;
    }

    public Map<String, List<ChoiceCopyVariant>> groupedByFamily() {
        Map<String, List<ChoiceCopyVariant>> loaded = new LinkedHashMap<>();
        for (ChoiceCopyVariantEntity entity : repository.findAllByOrderByFamily_IdAscVariantIndexAsc()) {
            loaded.computeIfAbsent(entity.getFamily().getId(), key -> new ArrayList<>())
                    .add(new ChoiceCopyVariant(entity.getLabel(), entity.getMeaning()));
        }
        return Map.copyOf(loaded);
    }

    public void saveVariant(StoryActionFamilyEntity family, int variantIndex, String label, String meaning) {
        repository.save(ChoiceCopyVariantEntity.builder()
                .family(family).variantIndex(variantIndex).label(label).meaning(meaning)
                .build());
    }
}
