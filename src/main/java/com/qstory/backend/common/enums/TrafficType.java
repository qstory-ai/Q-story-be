package com.qstory.backend.common.enums;

import java.util.List;

/**
 * 퍼널 세션의 트래픽 세그먼테이션 태그. Java enum이 아니라 일반 문자열인 이유: UNKNOWN 기본값
 * 이외에는 분기 로직이 없으며, 단순하고 계속 늘어날 수 있는 세그먼테이션 어휘 집합이기 때문이다.
 */
public final class TrafficType {

    public static final String UNKNOWN = "UNKNOWN";
    public static final String BETA = "BETA";
    public static final String QA = "QA";
    public static final String DEV = "DEV";

    public static final List<String> VALUES = List.of(UNKNOWN, BETA, QA, DEV);

    private TrafficType() {}
}
