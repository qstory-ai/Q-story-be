package com.qstory.backend.reportanalysis.service;

import com.qstory.backend.playsession.service.PlaySessionService;
import com.qstory.backend.story.service.StoryContentAssemblyService;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리포트 분석 작업을 15초마다 몇 개씩 가져가 만든다(report_analysis). 실패해도 기본 리포트는 그대로 보이고, 분석만
 * FAILED로 남아 "다시 시도"로 다시 넣을 수 있다. 서버 재시작으로 RUNNING에 멈춘 작업은 10분 뒤 다시 가져간다.
 */
@Component
public class ReportAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportAnalysisWorker.class);
    static final int BATCH = 3;

    private final ReportAnalysisStore store;
    private final ReportAnalysisService analysisService;
    private final StoryCompletionRepository completionRepository;
    private final PlaySessionService playSessionService;
    private final StoryContentAssemblyService contentAssemblyService;
    private final TransactionTemplate readOnly;

    public ReportAnalysisWorker(
            ReportAnalysisStore store, ReportAnalysisService analysisService,
            StoryCompletionRepository completionRepository, PlaySessionService playSessionService,
            StoryContentAssemblyService contentAssemblyService, TransactionTemplate transactionTemplate) {
        this.store = store;
        this.analysisService = analysisService;
        this.completionRepository = completionRepository;
        this.playSessionService = playSessionService;
        this.contentAssemblyService = contentAssemblyService;
        this.readOnly = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.readOnly.setReadOnly(true);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void runPending() {
        List<UUID> claimed;
        try {
            claimed = store.claim(BATCH);
        } catch (RuntimeException error) {
            log.error("report-analysis.claim-failed reason={}", error.toString());
            return;
        }
        for (UUID completionId : claimed) {
            runOne(completionId);
        }
    }

    void runOne(UUID completionId) {
        long startedAt = System.currentTimeMillis();
        try {
            Optional<ReportAnalysisService.Input> input = readOnly.execute(status -> loadInput(completionId));
            if (input == null || input.isEmpty()) {
                store.finish(completionId, "SKIPPED", null, ReportAnalysisService.PROMPT_VERSION, null, "기록 없음");
                return;
            }
            Map<String, Object> result = analysisService.analyze(input.get());
            store.finish(completionId, "READY", analysisService.modelId(), ReportAnalysisService.PROMPT_VERSION, result, null);
            log.info("report-analysis.ready completion_id={} observations={} duration_ms={}",
                    completionId, ((List<?>) result.get("observations")).size(), System.currentTimeMillis() - startedAt);
        } catch (RuntimeException error) {
            log.warn("report-analysis.failed completion_id={} reason={}", completionId, error.toString());
            store.finish(completionId, "FAILED", analysisService.modelId(), ReportAnalysisService.PROMPT_VERSION, null, error.toString());
        }
    }

    private Optional<ReportAnalysisService.Input> loadInput(UUID completionId) {
        Optional<StoryCompletion> found = completionRepository.findById(completionId);
        if (found.isEmpty()) return Optional.empty();
        StoryCompletion completion = found.get();
        var content = contentAssemblyService.get(completion.getStoryId());
        if (content == null) return Optional.empty();
        return Optional.of(new ReportAnalysisService.Input(
                completion.sessionKind(),
                playSessionService.listTurns(completion.getSessionId()),
                content,
                completion.getReadFromSceneId(),
                completion.getReadThroughSceneId()));
    }
}
