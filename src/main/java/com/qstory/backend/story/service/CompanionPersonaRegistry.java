package com.qstory.backend.story.service;

import com.qstory.backend.story.CompanionPersona;
import com.qstory.backend.story.entity.StoryPersona;
import com.qstory.backend.story.repository.StoryPersonaRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * story_persona(personas.yaml 임포트본)의 인메모리 캐시. ChoiceCopyRegistry와 같은 규칙 - 부팅 시
 * 한 번, 그리고 매 POST /v1/admin/stories/import 이후에 다시 Postgres로부터 로드된다
 * (StoryImportService 참고). 컴패니언 챗의 매 턴마다 읽히므로 요청마다 쿼리하지 않는다.
 *
 * <p>키는 (storyId, speakerId). 컴패니언 챗은 프론트가 고른 speakerId(예: HG-SPK-GRETEL)로
 * 상대를 지정하므로 castTag가 아니라 speakerId로 찾는다.
 */
@Component
@Order(1)
public class CompanionPersonaRegistry implements ApplicationRunner {

    private final StoryPersonaRepository repository;

    private volatile Map<String, Map<String, CompanionPersona>> personasByStory = Map.of();

    public CompanionPersonaRegistry(StoryPersonaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    public void reload() {
        Map<String, Map<String, CompanionPersona>> loaded = new LinkedHashMap<>();
        for (StoryPersona entity : repository.findAllWithStory()) {
            loaded.computeIfAbsent(entity.getStory().getId(), ignored -> new LinkedHashMap<>())
                    .put(entity.getSpeakerId(), toDomain(entity));
        }
        Map<String, Map<String, CompanionPersona>> frozen = new LinkedHashMap<>();
        loaded.forEach((storyId, bySpeaker) -> frozen.put(storyId, Map.copyOf(bySpeaker)));
        personasByStory = Map.copyOf(frozen);
    }

    /** 해당 스토리에 personas.yaml이 임포트되지 않았거나 그 화자의 시트가 없으면 null. */
    public CompanionPersona find(String storyId, String speakerId) {
        return personasByStory.getOrDefault(storyId, Map.of()).get(speakerId);
    }

    private static CompanionPersona toDomain(StoryPersona entity) {
        return new CompanionPersona(
                entity.getCastTag(),
                entity.getSpeakerId(),
                entity.getRole(),
                entity.getAgeBand(),
                copy(entity.getPersonalityTraits()),
                entity.getPersonalityOneLiner(),
                copy(entity.getSpeechEndings()),
                copy(entity.getSpeechCatchphrases()),
                entity.getSentenceLengthBias(),
                copy(entity.getEmotionRangeAllowed()),
                entity.getEmotionCapNote(),
                copy(entity.getKnowledgeKnows()),
                copy(entity.getKnowledgeDoesNotKnow()),
                copy(entity.getKnowledgeNeverRevealsFirst()));
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
