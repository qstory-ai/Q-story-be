package com.qstory.backend.storyadmin.dto;

/**
 * 하나의 segment payload에 대한 수정.
 *
 * <p>{@code payload}는 병합이 아니라 맵 전체를 교체한다: 병합으로는 키 제거를 표현할 수 없고,
 * segment payload는 kind별로 형태가 다르기 때문에, 부분 업데이트를 하면 지금 쓰려는 형태에 더 이상
 * 속하지 않는 필드가 조용히 남아 있게 된다.
 */
public record SegmentEditRequest(
        Integer baseRevision, java.util.Map<String, Object> payload, String summary) {}
