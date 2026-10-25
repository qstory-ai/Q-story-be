package com.qstory.backend.reportanalysis.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.playsession.service.PlaySessionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 로그인하지 않은 데모 회차의 리포트 분석. 데모 리포트를 열면 start로 작업을 넣고, 프런트가 find로 결과를 받아 로그인 리포트와
 * 같은 ④ 관심과 생각·⑤ 대화 카드를 그린다. 주인 없는 회차이고 betaSessionId가 맞을 때만 연다(회차 id만 알아서는 못 연다).
 */
@Service
public class DemoReportAnalysisService {

    private static final String NOT_FOUND = "이 체험 기록을 찾지 못했어요.";

    private final DemoReportAnalysisStore store;
    private final PlaySessionService playSessionService;

    public DemoReportAnalysisService(DemoReportAnalysisStore store, PlaySessionService playSessionService) {
        this.store = store;
        this.playSessionService = playSessionService;
    }

    public Map<String, Object> start(UUID sessionId, UUID betaSessionId, String readFromSceneId, String readThroughSceneId) {
        String storyId = playSessionService.anonymousStoryId(sessionId, betaSessionId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, NOT_FOUND, 404));
        store.enqueue(sessionId, storyId, clip(readFromSceneId), clip(readThroughSceneId));
        return current(sessionId);
    }

    public Map<String, Object> find(UUID sessionId, UUID betaSessionId) {
        if (playSessionService.anonymousStoryId(sessionId, betaSessionId).isEmpty()) {
            throw ApiException.contractError(ErrorCode.NOT_FOUND, NOT_FOUND, 404);
        }
        return current(sessionId);
    }

    private Map<String, Object> current(UUID sessionId) {
        Map<String, Object> analysis = store.find(sessionId);
        if (analysis != null) return analysis;
        Map<String, Object> none = new LinkedHashMap<>();
        none.put("status", "PENDING");
        none.put("observations", List.of());
        none.put("cards", List.of());
        none.put("commonScenes", List.of());
        return none;
    }

    private static String clip(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) return null;
        String trimmed = sceneId.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }
}
