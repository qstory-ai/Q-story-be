package com.qstory.backend.analytics.service;

import java.time.LocalDate;

/** GA 보고서 한 행을 테이블 한 행으로. 문자열 차원은 비어 있으면 '(not set)'. */
public final class GaRows {

    private GaRows() {}

    /** 날짜 × 호스트 × 유입 × 플랫폼·화면이 같은 행의 키. */
    public record Key(
            LocalDate date, String hostName, String source, String medium, String campaign,
            String platform, String surface) {}

    public record EventRow(Key key, String eventName, long eventCount, long totalUsers, long sessions) {}

    public record TrafficRow(
            Key key, long totalUsers, long newUsers, long sessions, long engagedSessions, long pageViews) {}
}
