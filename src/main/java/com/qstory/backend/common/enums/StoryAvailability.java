package com.qstory.backend.common.enums;

import java.util.List;

/**
 * 스토리의 발행 상태 태그. Java enum이 아니라 일반 문자열 값인 이유: 코드베이스 어디에도 컴파일
 * 타임에 고정된 이 값들의 집합에 따라 분기하는 로직이 없고(아래의 두 소비자 모두 enum을 임포트하는
 * 대신 이미 값 집합을 문자열 리터럴로 다시 타이핑해 두었다), 운영팀이 백엔드 재배포 없이도 새로운
 * 생명주기 값을 추가할 수 있어야 하기 때문이다.
 */
public final class StoryAvailability {

    public static final String INTERNAL = "INTERNAL";
    public static final String BETA = "BETA";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String RETIRED = "RETIRED";

    /** 현재 알려진 모든 값 - 쓰기 시점에 값을 검증하는 데 사용됨. */
    public static final List<String> VALUES = List.of(INTERNAL, BETA, PUBLISHED, RETIRED);

    /** 이 스토리에 대해 질문/내레이션 파이프라인이 실행될 수 있도록 허용하는 값들(StoryRegistryService 참조). */
    public static final List<String> ACTIVE = List.of(INTERNAL, BETA, PUBLISHED);

    private StoryAvailability() {}
}
