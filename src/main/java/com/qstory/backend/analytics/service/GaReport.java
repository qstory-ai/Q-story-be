package com.qstory.backend.analytics.service;

import java.util.List;

/**
 * 가져오는 GA 보고서 두 가지. 분할(차원)은 같고 EVENTS만 이벤트 이름이 더 있다.
 * 사용자·세션은 이벤트별 행을 더하면 중복으로 세어지므로 TRAFFIC을 따로 받는다.
 */
public enum GaReport {
    EVENTS(
            List.of("date", "hostName", "sessionSource", "sessionMedium", "sessionCampaignName",
                    "customUser:qs_platform", "customUser:qs_surface", "eventName"),
            List.of("eventCount", "totalUsers", "sessions")),
    TRAFFIC(
            List.of("date", "hostName", "sessionSource", "sessionMedium", "sessionCampaignName",
                    "customUser:qs_platform", "customUser:qs_surface"),
            List.of("totalUsers", "newUsers", "sessions", "engagedSessions", "screenPageViews"));

    private final List<String> dimensions;
    private final List<String> metrics;

    GaReport(List<String> dimensions, List<String> metrics) {
        this.dimensions = dimensions;
        this.metrics = metrics;
    }

    public List<String> dimensions() {
        return dimensions;
    }

    public List<String> metrics() {
        return metrics;
    }
}
