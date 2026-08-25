package com.qstory.backend.choicecopy.repository;
import com.qstory.backend.choicecopy.ChoiceCopyVariant;

import com.qstory.backend.story.entity.StoryActionFamily;
import com.qstory.backend.story.repository.StoryActionFamilyRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** StoryActionFamilyRepository를 감싸는 래퍼: ChoiceCopyRegistry를 위한 entity->domain 조립. */
@Component
public class ChoiceCopyRepository {

    private final StoryActionFamilyRepository repository;

    public ChoiceCopyRepository(StoryActionFamilyRepository repository) {
        this.repository = repository;
    }

    public Map<String, List<ChoiceCopyVariant>> groupedByFamily() {
        Map<String, List<ChoiceCopyVariant>> loaded = new LinkedHashMap<>();
        for (StoryActionFamily family : repository.findAll()) {
            loaded.put(family.getId(), family.getChoiceCopyVariants());
        }
        return Map.copyOf(loaded);
    }
}
