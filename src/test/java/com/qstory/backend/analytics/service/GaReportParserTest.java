package com.qstory.backend.analytics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class GaReportParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** runReport 응답 모양 그대로(v1beta REST JSON). */
    private static final String EVENTS_RESPONSE = """
            {
              "dimensionHeaders": [
                {"name": "date"}, {"name": "hostName"}, {"name": "sessionSource"}, {"name": "sessionMedium"},
                {"name": "sessionCampaignName"}, {"name": "customUser:qs_platform"}, {"name": "customUser:qs_surface"},
                {"name": "eventName"}
              ],
              "metricHeaders": [
                {"name": "eventCount", "type": "TYPE_INTEGER"},
                {"name": "totalUsers", "type": "TYPE_INTEGER"},
                {"name": "sessions", "type": "TYPE_INTEGER"}
              ],
              "rows": [
                {
                  "dimensionValues": [
                    {"value": "20261014"}, {"value": "demo.qstory.ai.kr"}, {"value": "qr"}, {"value": "offline"},
                    {"value": "qr-main"}, {"value": "web"}, {"value": "app"}, {"value": "story_start"}
                  ],
                  "metricValues": [{"value": "12"}, {"value": "7"}, {"value": "8"}]
                },
                {
                  "dimensionValues": [
                    {"value": "20261013"}, {"value": "qstory.ai.kr"}, {"value": "(direct)"}, {"value": "(none)"},
                    {"value": ""}, {"value": "(not set)"}, {"value": "(not set)"}, {"value": "page_view"}
                  ],
                  "metricValues": [{"value": "30"}, {"value": "20"}, {"value": "22"}]
                },
                {
                  "dimensionValues": [
                    {"value": "(other)"}, {"value": "(other)"}, {"value": "(other)"}, {"value": "(other)"},
                    {"value": "(other)"}, {"value": "(other)"}, {"value": "(other)"}, {"value": "(other)"}
                  ],
                  "metricValues": [{"value": "5"}, {"value": "5"}, {"value": "5"}]
                }
              ],
              "rowCount": 3,
              "metadata": {"currencyCode": "KRW", "timeZone": "Asia/Seoul"},
              "kind": "analyticsData#runReport"
            }
            """;

    @Test
    void eventRowsAreReadByHeaderNameAndBlankBecomesNotSet() throws Exception {
        GaReportParser.Page<GaRows.EventRow> page = GaReportParser.events(objectMapper.readTree(EVENTS_RESPONSE));

        assertEquals(3, page.rowCount());
        assertEquals(3, page.rawRows());
        assertEquals(2, page.rows().size(), "날짜를 읽을 수 없는 (other) 행은 건너뛴다");

        GaRows.EventRow qr = page.rows().get(0);
        assertEquals(LocalDate.of(2026, 10, 14), qr.key().date());
        assertEquals("demo.qstory.ai.kr", qr.key().hostName());
        assertEquals("qr", qr.key().source());
        assertEquals("offline", qr.key().medium());
        assertEquals("qr-main", qr.key().campaign());
        assertEquals("web", qr.key().platform());
        assertEquals("app", qr.key().surface());
        assertEquals("story_start", qr.eventName());
        assertEquals(12, qr.eventCount());
        assertEquals(7, qr.totalUsers());
        assertEquals(8, qr.sessions());

        GaRows.EventRow direct = page.rows().get(1);
        assertEquals("(not set)", direct.key().campaign());
        assertEquals("(none)", direct.key().medium());
    }

    @Test
    void headerOrderWinsOverRequestOrder() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "dimensionHeaders": [
                    {"name": "customUser:qs_surface"}, {"name": "customUser:qs_platform"}, {"name": "sessionCampaignName"},
                    {"name": "sessionMedium"}, {"name": "sessionSource"}, {"name": "hostName"}, {"name": "date"}
                  ],
                  "metricHeaders": [
                    {"name": "screenPageViews"}, {"name": "engagedSessions"}, {"name": "sessions"},
                    {"name": "newUsers"}, {"name": "totalUsers"}
                  ],
                  "rows": [{
                    "dimensionValues": [
                      {"value": "app"}, {"value": "ios"}, {"value": "launch"}, {"value": "social"},
                      {"value": "instagram"}, {"value": "app.qstory.ai.kr"}, {"value": "20261012"}
                    ],
                    "metricValues": [{"value": "50"}, {"value": "9"}, {"value": "10"}, {"value": "4"}, {"value": "6"}]
                  }],
                  "rowCount": 1
                }
                """);

        GaRows.TrafficRow row = GaReportParser.traffic(response).rows().get(0);

        assertEquals(new GaRows.Key(LocalDate.of(2026, 10, 12), "app.qstory.ai.kr", "instagram", "social", "launch", "ios", "app"),
                row.key());
        assertEquals(6, row.totalUsers());
        assertEquals(4, row.newUsers());
        assertEquals(10, row.sessions());
        assertEquals(9, row.engagedSessions());
        assertEquals(50, row.pageViews());
    }

    @Test
    void emptyReportHasNoRows() throws Exception {
        JsonNode response = objectMapper.readTree("{\"kind\":\"analyticsData#runReport\",\"metadata\":{}}");

        GaReportParser.Page<GaRows.TrafficRow> page = GaReportParser.traffic(response);

        assertTrue(page.rows().isEmpty());
        assertEquals(0, page.rowCount());
    }

    @Test
    void oddMetricValuesDoNotBreakParsing() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {"rows": [{
                  "dimensionValues": [
                    {"value": "20261014"}, {"value": "demo.qstory.ai.kr"}, {"value": "google"}, {"value": "organic"},
                    {"value": "(organic)"}, {"value": "android"}, {"value": "app"}, {"value": "%s"}
                  ],
                  "metricValues": [{"value": "3.6"}, {"value": "x"}]
                }], "rowCount": 1}
                """.formatted("x".repeat(400)));

        GaRows.EventRow row = GaReportParser.events(response).rows().get(0);

        assertEquals(4, row.eventCount(), "소수는 반올림");
        assertEquals(0, row.totalUsers(), "숫자가 아니면 0");
        assertEquals(0, row.sessions(), "없는 열은 0");
        assertEquals(GaReportParser.MAX_TEXT, row.eventName().length(), "긴 값은 잘라서 넣는다");
    }
}
