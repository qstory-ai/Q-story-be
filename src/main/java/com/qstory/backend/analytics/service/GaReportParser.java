package com.qstory.backend.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * runReport 응답(JSON)을 행으로 푼다. 열 순서는 요청 순서를 믿지 않고 dimensionHeaders/metricHeaders의 이름으로 찾는다.
 * 날짜를 읽을 수 없는 행(GA가 행 수 한도를 넘기면 만드는 '(other)' 등)은 건너뛴다.
 */
public final class GaReportParser {

    static final String NOT_SET = "(not set)";
    static final int MAX_TEXT = 300;
    private static final DateTimeFormatter GA_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private GaReportParser() {}

    /** 한 페이지 결과 - rows와 전체 행 수(rowCount, 페이지 넘김 판단용). */
    public record Page<T>(List<T> rows, int rowCount, int rawRows) {}

    public static Page<GaRows.EventRow> events(JsonNode response) {
        Columns columns = Columns.of(response, GaReport.EVENTS);
        List<GaRows.EventRow> rows = new ArrayList<>();
        for (JsonNode row : response.path("rows")) {
            GaRows.Key key = key(row, columns);
            if (key == null) {
                continue;
            }
            rows.add(new GaRows.EventRow(
                    key,
                    text(row, columns, "eventName"),
                    metric(row, columns, "eventCount"),
                    metric(row, columns, "totalUsers"),
                    metric(row, columns, "sessions")));
        }
        return new Page<>(rows, response.path("rowCount").asInt(0), response.path("rows").size());
    }

    public static Page<GaRows.TrafficRow> traffic(JsonNode response) {
        Columns columns = Columns.of(response, GaReport.TRAFFIC);
        List<GaRows.TrafficRow> rows = new ArrayList<>();
        for (JsonNode row : response.path("rows")) {
            GaRows.Key key = key(row, columns);
            if (key == null) {
                continue;
            }
            rows.add(new GaRows.TrafficRow(
                    key,
                    metric(row, columns, "totalUsers"),
                    metric(row, columns, "newUsers"),
                    metric(row, columns, "sessions"),
                    metric(row, columns, "engagedSessions"),
                    metric(row, columns, "screenPageViews")));
        }
        return new Page<>(rows, response.path("rowCount").asInt(0), response.path("rows").size());
    }

    private static GaRows.Key key(JsonNode row, Columns columns) {
        LocalDate date;
        try {
            date = LocalDate.parse(rawDimension(row, columns, "date"), GA_DATE);
        } catch (DateTimeParseException | NullPointerException unreadable) {
            return null;
        }
        return new GaRows.Key(
                date,
                text(row, columns, "hostName"),
                text(row, columns, "sessionSource"),
                text(row, columns, "sessionMedium"),
                text(row, columns, "sessionCampaignName"),
                text(row, columns, "customUser:qs_platform"),
                text(row, columns, "customUser:qs_surface"));
    }

    private static String rawDimension(JsonNode row, Columns columns, String name) {
        Integer index = columns.dimensions.get(name);
        if (index == null) {
            return null;
        }
        JsonNode value = row.path("dimensionValues").path(index).path("value");
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    static String text(JsonNode row, Columns columns, String name) {
        String value = rawDimension(row, columns, name);
        if (value == null || value.isBlank()) {
            return NOT_SET;
        }
        String trimmed = value.trim();
        return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) : trimmed;
    }

    static long metric(JsonNode row, Columns columns, String name) {
        Integer index = columns.metrics.get(name);
        if (index == null) {
            return 0;
        }
        String value = row.path("metricValues").path(index).path("value").asText("");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException notInteger) {
            try {
                double number = Double.parseDouble(value);
                return Double.isFinite(number) ? Math.round(number) : 0;
            } catch (NumberFormatException notNumber) {
                return 0;
            }
        }
    }

    /** 이름 → 열 번호. 헤더가 없으면(빈 결과) 요청 순서를 쓴다. */
    record Columns(Map<String, Integer> dimensions, Map<String, Integer> metrics) {

        static Columns of(JsonNode response, GaReport report) {
            return new Columns(
                    indexes(response.path("dimensionHeaders"), report.dimensions()),
                    indexes(response.path("metricHeaders"), report.metrics()));
        }

        private static Map<String, Integer> indexes(JsonNode headers, List<String> fallback) {
            Map<String, Integer> indexes = new HashMap<>();
            if (headers.isArray() && !headers.isEmpty()) {
                for (int i = 0; i < headers.size(); i++) {
                    indexes.put(headers.get(i).path("name").asText(), i);
                }
            } else {
                for (int i = 0; i < fallback.size(); i++) {
                    indexes.put(fallback.get(i), i);
                }
            }
            return indexes;
        }
    }
}
