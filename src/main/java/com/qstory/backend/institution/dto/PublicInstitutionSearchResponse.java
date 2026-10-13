package com.qstory.backend.institution.dto;

import java.util.List;

/**
 * @param hasMore limit보다 더 있으면 true - 앱은 "검색어를 더 자세히" 안내만 하고 다음 쪽을 부르지 않는다(깊은 쪽 넘김을 막는다).
 * @param attribution 출처 표기(공공누리 제1유형 조건) - 화면에 그대로 보여 준다.
 */
public record PublicInstitutionSearchResponse(
        List<PublicInstitutionResponse> items, boolean hasMore, String attribution) {}
