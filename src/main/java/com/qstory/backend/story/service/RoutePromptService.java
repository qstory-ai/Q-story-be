package com.qstory.backend.story.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.story.entity.RoutePrompt;
import com.qstory.backend.story.repository.RoutePromptRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스토리가 지정하는 route policy를 읽어온다. 버전별로 캐싱하는 이유는 주어진 버전 안에서는 텍스트가
 * 불변이기 때문이다 - policy가 바뀌면 새 버전이 되며, 이것이 바로 텍스트를 Java 안에 두지 않고
 * 두 값을 함께 저장하는 이유의 전부다.
 */
@Service
public class RoutePromptService {

    private final RoutePromptRepository repository;
    private final Map<String, Prompt> cache = new ConcurrentHashMap<>();

    public RoutePromptService(RoutePromptRepository repository) {
        this.repository = repository;
    }

    public record Prompt(String systemText, String instructionText, String companionSafetyFragment) {}

    @Transactional(readOnly = true)
    public Prompt requirePrompt(String version) {
        return cache.computeIfAbsent(version, key -> repository.findByVersion(key)
                .map(this::toPrompt)
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.INTERNAL_ERROR,
                        "질문을 처리할 준비가 아직 끝나지 않았어요.",
                        500)));
    }

    /** 재임포트된 policy가 오래된 캐시에서 제공되지 않도록 임포트 경로에서 호출된다. */
    public void invalidate(String version) {
        cache.remove(version);
    }

    private Prompt toPrompt(RoutePrompt row) {
        return new Prompt(row.getSystemText(), row.getInstructionText(), row.getCompanionSafetyFragment());
    }
}
