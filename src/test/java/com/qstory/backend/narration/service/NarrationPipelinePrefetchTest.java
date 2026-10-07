package com.qstory.backend.narration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.gemini.util.GeminiTtsClient;
import com.qstory.backend.provider.openrouter.SynthesizedAudioStream;
import com.qstory.backend.story.CastEntry;
import com.qstory.backend.story.service.StoryRegistryService.ResolvedNarrationContext;
import com.qstory.backend.voicecast.service.VoiceCastService;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class NarrationPipelinePrefetchTest {

    private final GeminiTtsClient mainClient = mock(GeminiTtsClient.class);
    private final GeminiTtsClient prefetchClient = mock(GeminiTtsClient.class);
    private final VoiceCastService voiceCast = mock(VoiceCastService.class);
    private final CastEntry cast = mock(CastEntry.class);
    private final ResolvedNarrationContext context = new ResolvedNarrationContext(null, null, cast);

    private NarrationPipelineService pipeline(String prefetchKey) {
        AppProperties config = mock(AppProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.providers().gemini()).thenReturn(new AppProperties.Gemini("main-key", "model", "voice", prefetchKey));
        when(voiceCast.buildGeminiTtsPerformanceInput(anyString(), anyString(), anyString())).thenReturn("input");
        when(cast.voice()).thenReturn("Sulafat");
        return new NarrationPipelineService(config, mainClient, prefetchClient, voiceCast);
    }

    private static SynthesizedAudioStream audio() {
        return new SynthesizedAudioStream(new ByteArrayInputStream(new byte[0]), "audio/pcm", 24000, 1, 16, null);
    }

    @Test
    void prefetchWithoutKeyIsRejectedBeforeAnyTtsCall() {
        for (String key : new String[] {"", "  ", null}) {
            NarrationPipelineService service = pipeline(key);
            ApiException error = assertThrows(ApiException.class, () ->
                    service.processStream(context, "s", "sp", "text", RequestDeadline.startingNow(1000), true));
            assertEquals(ErrorCode.PREFETCH_DISABLED, error.code());
            assertEquals(409, error.statusCode());
        }
        verifyNoInteractions(mainClient, prefetchClient);
    }

    @Test
    void prefetchWithKeyUsesPrefetchClient() {
        NarrationPipelineService service = pipeline("pk");
        SynthesizedAudioStream stream = audio();
        when(prefetchClient.synthesizeStream(anyString(), eq("Sulafat"), anyDouble(), any())).thenReturn(stream);

        NarrationPipelineService.StreamResult result =
                service.processStream(context, "s", "sp", "text", RequestDeadline.startingNow(1000), true);

        assertSame(stream, result.audio());
        verifyNoInteractions(mainClient);
    }

    @Test
    void withoutPrefetchUsesMainClient() {
        NarrationPipelineService service = pipeline("");
        SynthesizedAudioStream stream = audio();
        when(mainClient.synthesizeStream(anyString(), eq("Sulafat"), anyDouble(), any())).thenReturn(stream);

        NarrationPipelineService.StreamResult result =
                service.processStream(context, "s", "sp", "text", RequestDeadline.startingNow(1000), false);

        assertSame(stream, result.audio());
        verify(prefetchClient, never()).synthesizeStream(any(), any(), anyDouble(), any());
    }
}
