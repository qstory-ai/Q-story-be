package com.qstory.backend.common.enums;

/**
 * 스토리 에셋이 무엇인지를, id에 대한 네이밍 컨벤션이 아니라 진짜 enum으로 나타낸다.
 *
 * <p>예전에는 이것이 id 접두사({@code HG-ART-}/{@code HG-AUD-})와, 항목이 assets.json의 네 개
 * 병렬 맵 중 어디에 들어있었는지로부터 추론되었는데, 그래서 같은 삽화가 콘텐츠 파일들에 걸쳐 다섯 가지
 * 다른 방식으로 표기될 수 있었다. 이제 카테고리는 하나의 컬럼이 되었고, slug는 타입 정보를 전혀
 * 담지 않는다.
 */
public enum AssetCategory {
    /** 씬의 비주얼 세그먼트가 보여주는 고정 삽화. */
    SCENE_ART,
    /** 하나의 액션 패밀리 분기의 삽화로, 아이가 선택하는 동안 표시된다. */
    BRANCH_ART,
    /** 고정된 하나의 발화에 대해 미리 렌더링된 내레이션. */
    NARRATION,
    /** 분기로 라우팅되는 동안 재생되는 짧은 응답(acknowledgement) 클립. */
    BRIDGE,
}
