package com.qstory.backend.storyadmin.dto;

/**
 * 하나의 scene에서 저작(authoring)된 필드에 대한 수정.
 *
 * <p>{@code baseRevision}은 편집자가 보고 있던 스토리 리비전이다. 이 값은 선택이 아니라 필수다:
 * 이 값이 없으면 같은 스토리를 편집하는 두 사람이 서로를 조용히 덮어쓰게 되는데, 이는 콘텐츠
 * 파일에서는 결코 일어날 수 없었던 실패 양상이다. git이 두 번째 push를 거부했기 때문이다.
 */
public record SceneEditRequest(Integer baseRevision, String title, Integer sequence, String summary) {}
