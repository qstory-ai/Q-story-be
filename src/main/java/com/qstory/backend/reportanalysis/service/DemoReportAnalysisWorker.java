package com.qstory.backend.reportanalysis.service;

import com.qstory.backend.playsession.service.PlaySessionService;
import com.qstory.backend.story.service.StoryContentAssemblyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 데모 리포트 분석(demo_report_analysis)을 3초마다 가져가 만든다 - 아이와 부모가 리포트 화면을 보고 있는 동안이라
 * 계정 리포트(15초마다)보다 자주 본다. 분석 방법은 계정 리포트와 같다(ReportAnalysisService).
 */
@Component
public class DemoReportAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(DemoReportAnalysisWorker.class);
    static final int BATCH = 3;

    private final DemoReportAnalysisStore store;
    private final ReportAnalysisService analysisService;
    private final PlaySessionService playSessionService;
    private final StoryContentAssemblyService contentAssemblyService;

    public DemoReportAnalysisWorker(
            DemoReportAnalysisStore store, ReportAnalysisService analysisService,
            PlaySessionService playSessionService, StoryContentAssemblyService contentAssemblyService) {
        this.store = store;
        this.analysisService = analysisService;
        this.playSessionService = playSessionService;
        this.contentAssemblyService = contentAssemblyService;
    }

    @Scheduled(fixedDelay = 3_000, initialDelay = 20_000)
    public void runPending() {
        List<UUID> claimed;
        try {
            claimed = store.claim(BATCH);
        } catch (RuntimeException error) {
            log.error("demo-report-analysis.claim-failed reason={}", error.toString());
            return;
        }
        for (UUID sessionId : claimed) {
            runOne(sessionId);
        }
    }

    void runOne(UUID sessionId) {
        long startedAt = System.currentTimeMillis();
        try {
            DemoReportAnalysisStore.Job job = store.job(sessionId);
            List<Map<String, Object>> turns = job == null ? List.of() : playSessionService.listAnonymousTurns(sessionId);
            var content = job == null ? null : contentAssemblyService.get(job.storyId());
            if (job == null || content == null || turns.isEmpty()) {
                store.finish(sessionId, "SKIPPED", null, ReportAnalysisService.PROMPT_VERSION, null, "기록 없음");
                return;
            }
            Map<String, Object> result = analysisService.analyze(new ReportAnalysisService.Input(
                    "HOME", turns, content, job.readFromSceneId(), job.readThroughSceneId()));
            store.finish(sessionId, "READY", analysisService.modelId(), ReportAnalysisService.PROMPT_VERSION, result, null);
            log.info("demo-report-analysis.ready observations={} duration_ms={}",
                    ((List<?>) result.get("observations")).size(), System.currentTimeMillis() - startedAt);
        } catch (RuntimeException error) {
            log.warn("demo-report-analysis.failed reason={}", error.toString());
            store.finish(sessionId, "FAILED", analysisService.modelId(), ReportAnalysisService.PROMPT_VERSION, null, error.toString());
        }
    }
}
