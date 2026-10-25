package com.qstory.backend.reportanalysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.playsession.service.PlaySessionService;
import com.qstory.backend.story.service.StoryContentAssemblyService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemoReportAnalysisServiceTest {

    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID BETA = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private final DemoReportAnalysisStore store = mock(DemoReportAnalysisStore.class);
    private final PlaySessionService playSessions = mock(PlaySessionService.class);
    private final DemoReportAnalysisService service = new DemoReportAnalysisService(store, playSessions);

    @Test
    void startQueuesTheSessionsOwnStory() {
        when(playSessions.anonymousStoryId(SESSION, BETA)).thenReturn(Optional.of("HG"));
        when(store.find(SESSION)).thenReturn(null);

        Map<String, Object> analysis = service.start(SESSION, BETA, "HG-F01", "HG-F10");

        verify(store).enqueue(SESSION, "HG", "HG-F01", "HG-F10");
        assertThat(analysis).containsEntry("status", "PENDING");
    }

    @Test
    void someoneElsesSessionIsNotFound() {
        when(playSessions.anonymousStoryId(SESSION, BETA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(SESSION, BETA, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.find(SESSION, BETA)).isInstanceOf(ApiException.class);
        verify(store, never()).enqueue(any(), anyString(), any(), any());
    }

    @Test
    void findReturnsTheStoredAnalysis() {
        when(playSessions.anonymousStoryId(SESSION, BETA)).thenReturn(Optional.of("HG"));
        when(store.find(SESSION)).thenReturn(Map.of("status", "READY", "observations", List.of(Map.of("key", "a"))));

        assertThat(service.find(SESSION, BETA)).containsEntry("status", "READY");
    }

    @Test
    void workerSkipsSessionsWithoutTurns() {
        ReportAnalysisService analysis = mock(ReportAnalysisService.class);
        StoryContentAssemblyService content = mock(StoryContentAssemblyService.class);
        DemoReportAnalysisWorker worker = new DemoReportAnalysisWorker(store, analysis, playSessions, content);
        when(store.job(SESSION)).thenReturn(new DemoReportAnalysisStore.Job(SESSION, "HG", null, null));
        when(content.get("HG")).thenReturn(JsonNodeFactory.instance.objectNode());
        when(playSessions.listAnonymousTurns(SESSION)).thenReturn(List.of());

        worker.runOne(SESSION);

        verify(store).finish(eq(SESSION), eq("SKIPPED"), any(), any(), any(), any());
        verify(analysis, never()).analyze(any());
    }

    @Test
    void workerStoresTheAnalysisOrTheFailure() {
        ReportAnalysisService analysis = mock(ReportAnalysisService.class);
        StoryContentAssemblyService content = mock(StoryContentAssemblyService.class);
        DemoReportAnalysisWorker worker = new DemoReportAnalysisWorker(store, analysis, playSessions, content);
        when(store.job(SESSION)).thenReturn(new DemoReportAnalysisStore.Job(SESSION, "HG", "HG-F01", "HG-F10"));
        when(content.get("HG")).thenReturn(JsonNodeFactory.instance.objectNode());
        when(playSessions.listAnonymousTurns(SESSION)).thenReturn(List.of(Map.of("seq", 1, "role", "CHILD", "text", "왜?")));
        when(analysis.analyze(any())).thenReturn(Map.of("observations", List.of(), "cards", List.of()));

        worker.runOne(SESSION);
        verify(store).finish(eq(SESSION), eq("READY"), any(), any(), any(), eq(null));

        when(analysis.analyze(any())).thenThrow(new IllegalStateException("model down"));
        worker.runOne(SESSION);
        verify(store).finish(eq(SESSION), eq("FAILED"), any(), any(), eq(null), anyString());
    }
}
