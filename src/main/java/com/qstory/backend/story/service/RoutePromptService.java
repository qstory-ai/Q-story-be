package com.qstory.backend.story.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.story.repository.RoutePromptRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스토리가 지정한 route_prompt 버전에서 그레텔 대화가 함께 쓰는 안전 규칙 블록
 * (companion_safety_fragment)만 읽어 준다. route_prompt의 system/instruction 텍스트는 옛 질문 라우팅이
 * 쓰던 것이라 임포트가 저장만 하고 런타임에서는 읽지 않는다.
 *
 * <p>버전별로 캐싱하는 이유는 한 버전 안에서는 텍스트가 바뀌지 않기 때문이다 - 정책이 바뀌면 새 버전이
 * 되고, 같은 버전을 다시 임포트할 때는 {@link #invalidate}로 캐시를 비운다.
 */
@Service
public class RoutePromptService {

    private final RoutePromptRepository repository;
    // ConcurrentHashMap은 null 값을 담지 못해 백필 전(null) 행은 빈 문자열로 캐싱한다 - 호출부가
    // null과 빈 문자열을 똑같이 "안전 블록 없음"으로 다룬다.
    private final Map<String, String> safetyFragmentCache = new ConcurrentHashMap<>();

    public RoutePromptService(RoutePromptRepository repository) {
        this.repository = repository;
    }

    /** 해당 버전의 안전 규칙 블록. 아직 백필되지 않았으면 빈 문자열, 버전 자체가 없으면 500. */
    @Transactional(readOnly = true)
    public String requireCompanionSafetyFragment(String version) {
        return safetyFragmentCache.computeIfAbsent(version, key -> repository.findByVersion(key)
                .map(row -> row.getCompanionSafetyFragment() == null ? "" : row.getCompanionSafetyFragment())
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.INTERNAL_ERROR,
                        "질문을 처리할 준비가 아직 끝나지 않았어요.",
                        500)));
    }

    /** 재임포트된 정책이 오래된 캐시에서 제공되지 않도록 임포트 경로에서 호출된다. */
    public void invalidate(String version) {
        safetyFragmentCache.remove(version);
    }
}
