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
 * Reads the route policy a story names. Cached per version because the text is immutable under a
 * given version - a changed policy is a new version, which is the whole point of storing the two
 * together instead of leaving the text in Java.
 */
@Service
public class RoutePromptService {

    private final RoutePromptRepository repository;
    private final Map<String, Prompt> cache = new ConcurrentHashMap<>();

    public RoutePromptService(RoutePromptRepository repository) {
        this.repository = repository;
    }

    public record Prompt(String systemText, String instructionText) {}

    @Transactional(readOnly = true)
    public Prompt requirePrompt(String version) {
        return cache.computeIfAbsent(version, key -> repository.findByVersion(key)
                .map(this::toPrompt)
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.INTERNAL_ERROR,
                        "질문을 처리할 준비가 아직 끝나지 않았어요.",
                        500)));
    }

    /** Called by the import path so a re-imported policy is not served from a stale cache. */
    public void invalidate(String version) {
        cache.remove(version);
    }

    private Prompt toPrompt(RoutePrompt row) {
        return new Prompt(row.getSystemText(), row.getInstructionText());
    }
}
