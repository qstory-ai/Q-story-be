package com.qstory.backend.provider.openrouter;

/**
 * 2단계 route_classifier의 검증된 출력. actionFamilyId/rejoinAnchorId/fallbackFamilyId는 오늘의
 * RouteDecision과 필드 단위로 호환되도록 여기서 되살렸다(사용자 결정 - 분류기가 allowedFamilies를
 * 이미 입력으로 받으므로 DIRECT_ACTION/SCENE_REPLACE/DETOUR_REJOIN일 때 이 필드들도 함께 고른다).
 * speakerId는 3단계(콘텐츠 생성)가 아니라 여기서 정한다 - 화자 선택은 콘텐츠가 아니라 장면/문맥
 * 수준의 판단이기 때문이다.
 */
public record RouteClassification(
        String route,
        String matchedGate,
        String coverageStatus,
        String coverageReason,
        String childRelevantMeaning,
        String actionFamilyId,
        String rejoinAnchorId,
        String fallbackFamilyId,
        String speakerId,
        String modelId) {}
