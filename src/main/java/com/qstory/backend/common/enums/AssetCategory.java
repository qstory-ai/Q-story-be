package com.qstory.backend.common.enums;

/** 스토리 에셋의 종류(story_asset.category). slug는 타입 정보를 담지 않으므로 종류는 이 값으로만 판단한다. */
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
