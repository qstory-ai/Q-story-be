package com.qstory.backend.story.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.enums.RoutePromptStageKind;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.provider.openrouter.FewShotExample;
import com.qstory.backend.story.entity.RoutePrompt;
import com.qstory.backend.story.entity.RoutePromptStage;
import com.qstory.backend.story.repository.RoutePromptRepository;
import com.qstory.backend.story.repository.RoutePromptStageRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스토리가 지정하는 route policy를 읽어온다. 버전별로 캐싱하는 이유는 주어진 버전 안에서는 텍스트가
 * 불변이기 때문이다 - policy가 바뀌면 새 버전이 되며, 이것이 바로 텍스트를 Java 안에 두지 않고
 * 두 값을 함께 저장하는 이유의 전부다.
 *
 * <p>Phase 2부터는 단일 호출 route policy(RoutePrompt) 옆에 3단계 파이프라인(SAFETY/CLASSIFIER/
 * GENERATOR) 전용 프롬프트(RoutePromptStage)도 함께 캐싱한다 - {@link #requireStage} 참고.
 */
@Service
public class RoutePromptService {

    private final RoutePromptRepository repository;
    private final RoutePromptStageRepository stageRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, Prompt> cache = new ConcurrentHashMap<>();
    private final Map<String, StagePrompt> stageCache = new ConcurrentHashMap<>();

    public RoutePromptService(
            RoutePromptRepository repository, RoutePromptStageRepository stageRepository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.stageRepository = stageRepository;
        this.objectMapper = objectMapper;
    }

    public record Prompt(String systemText, String instructionText, String companionSafetyFragment) {}

    /** 3단계 파이프라인 한 stage의 system 프롬프트 + few-shot 예시 목록. */
    public record StagePrompt(String systemText, List<FewShotExample> examples) {}

    @Transactional(readOnly = true)
    public Prompt requirePrompt(String version) {
        return cache.computeIfAbsent(version, key -> repository.findByVersion(key)
                .map(this::toPrompt)
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.INTERNAL_ERROR,
                        "질문을 처리할 준비가 아직 끝나지 않았어요.",
                        500)));
    }

    @Transactional(readOnly = true)
    public StagePrompt requireStage(String version, RoutePromptStageKind stage) {
        return stageCache.computeIfAbsent(stageCacheKey(version, stage), key -> stageRepository
                .findByRoutePromptVersionAndStage(version, stage)
                .map(this::toStagePrompt)
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.INTERNAL_ERROR,
                        "질문을 처리할 준비가 아직 끝나지 않았어요.",
                        500)));
    }

    /** 재임포트된 policy가 오래된 캐시에서 제공되지 않도록 임포트 경로에서 호출된다. */
    public void invalidate(String version) {
        cache.remove(version);
        for (RoutePromptStageKind stage : RoutePromptStageKind.values()) {
            stageCache.remove(stageCacheKey(version, stage));
        }
    }

    private String stageCacheKey(String version, RoutePromptStageKind stage) {
        return version + "::" + stage.name();
    }

    private Prompt toPrompt(RoutePrompt row) {
        return new Prompt(row.getSystemText(), row.getInstructionText(), row.getCompanionSafetyFragment());
    }

    /**
     * examples_json의 각 원소를 미리 문자열로 직렬화해 둔다 - (version, stage)당 불변인 내용을
     * 매 질문마다 OpenRouterClient가 다시 직렬화하지 않도록.
     */
    private StagePrompt toStagePrompt(RoutePromptStage row) {
        List<FewShotExample> examples = row.getExamples().stream()
                .map(example -> new FewShotExample(
                        objectMapper.valueToTree(example.get("input")).toString(),
                        objectMapper.valueToTree(example.get("output")).toString()))
                .toList();
        return new StagePrompt(row.getSystemText(), examples);
    }
}
