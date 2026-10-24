package com.qstory.backend.discord.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qstory.backend.discord.repository.DiscordMetricsRepository.Funnel;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.GaTraffic;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.Feedback;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.JoinRequests;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.Partnership;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.Surveys;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscordMessagesTest {

    static Map<String, Long> signups(long parent, long tutor, long director) {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("PARENT", parent);
        map.put("TUTOR", tutor);
        map.put("DIRECTOR", director);
        return map;
    }

    @Test
    void dailySummaryShowsDeltasAgainstDayBefore() {
        DiscordMessages.Day today = new DiscordMessages.Day(LocalDate.of(2026, 10, 13), new GaTraffic(120, 150, 10, 12),
                new Funnel(30, 12, 8), signups(2, 1, 0), 2, new Surveys(4, 4.5, 4.0));
        DiscordMessages.Day before = new DiscordMessages.Day(LocalDate.of(2026, 10, 12), new GaTraffic(108, 153, 8, 10),
                new Funnel(25, 12, 7), signups(1, 1, 0), 3, new Surveys(3, 4.0, 4.0));

        String text = DiscordMessages.dailyMetrics(today, before, "https://grafana.test/d/qstory-growth");

        assertTrue(text.startsWith("**[아침 지표]** 10/13(화)"), text);
        assertTrue(text.contains("방문자 120 (+12) · 세션 150 (-3)"), text);
        assertTrue(text.contains("QR 유입 방문자 10 (+2) · 세션 12 (+2)"), text);
        assertTrue(text.contains("데모 시작 30 (+5) · 이야기 완주 12 (±0) · 리포트 열람 8 (+1)"), text);
        assertTrue(text.contains("가입 3 (+1) - 보호자 2 · 선생님 1 · 원장 0"), text);
        assertTrue(text.contains("출시 알림 신청 2 (-1)"), text);
        assertTrue(text.contains("체험 후기 4 (+1) · 아이 몰입 ★4.5 · 리포트 도움 ★4.0"), text);
        assertTrue(text.endsWith("<https://grafana.test/d/qstory-growth>"), text);
    }

    @Test
    void dailySummaryWithoutGaDropsOnlyThatPart() {
        DiscordMessages.Day today = new DiscordMessages.Day(LocalDate.of(2026, 10, 13), null,
                new Funnel(1, 0, 0), signups(0, 0, 0), 0, new Surveys(0, null, null));
        String text = DiscordMessages.dailyMetrics(today, today, null);

        assertTrue(text.contains("방문자·세션: GA 집계 없음"), text);
        assertFalse(text.contains("QR"), text);
        assertTrue(text.contains("체험 후기 0 (±0)"), text);
        assertFalse(text.contains("★"), text);
    }

    @Test
    void digestListsOnlyNonZeroItems() {
        DiscordMessages.Window window = new DiscordMessages.Window(LocalTime.of(14, 0), LocalTime.of(14, 30),
                new Surveys(2, 4.5, 5.0), 0, 1, new JoinRequests(1, 3), signups(2, 1, 0), List.of(), List.of());

        String text = DiscordMessages.newsDigest(window, null);

        assertEquals("**[사용자 반응]** 14:00~14:30\n"
                + "체험 후기 2건 (아이 몰입 ★4.5 · 리포트 도움 ★5.0)\n"
                + "새 기관 등록 1곳\n"
                + "선생님 소속 신청 1건 (원장 수락 대기 총 3건)\n"
                + "새 가입 3명 - 보호자 2 · 선생님 1", text);
    }

    @Test
    void windowWithOnlyOldPendingRequestsIsEmpty() {
        DiscordMessages.Window window = new DiscordMessages.Window(LocalTime.of(14, 0), LocalTime.of(14, 30),
                new Surveys(0, null, null), 0, 0, new JoinRequests(0, 5), signups(0, 0, 0), List.of(), List.of());
        assertTrue(window.empty());
    }

    @Test
    void digestListsMyPageRequestsWithMaskedContactsAndNoMentions() {
        List<Feedback> feedback = List.of(
                new Feedback("[오류 제보] 녹음이 안 돼요 연락은 parent@example.com 010-1234-5678 @everyone", "PARENT"),
                new Feedback("[기능 제안] 글씨를 더 크게", null));
        DiscordMessages.Window window = new DiscordMessages.Window(LocalTime.of(14, 0), LocalTime.of(14, 30),
                new Surveys(0, null, null), 0, 0, new JoinRequests(0, 0), signups(0, 0, 0), feedback, List.of());

        String text = DiscordMessages.newsDigest(window, null);

        assertFalse(window.empty());
        assertEquals("**[사용자 반응]** 14:00~14:30\n"
                + "마이페이지 요청 2건 (오류 제보 1 · 기능 제안 1)\n"
                + "> (보호자) [오류 제보] 녹음이 안 돼요 연락은 (이메일) (전화번호) @\u200beveryone\n"
                + "> [기능 제안] 글씨를 더 크게", text);
    }

    @Test
    void longFeedbackIsCutAndExtraCounted() {
        List<Feedback> feedback = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> new Feedback("[기능 제안] " + "가".repeat(150), "TUTOR")).toList();
        DiscordMessages.Window window = new DiscordMessages.Window(LocalTime.of(14, 0), LocalTime.of(14, 30),
                new Surveys(0, null, null), 0, 0, new JoinRequests(0, 0), signups(0, 0, 0), feedback, List.of());

        String text = DiscordMessages.newsDigest(window, null);

        assertTrue(text.contains("> 외 2건"), text);
        assertTrue(text.lines().filter(line -> line.startsWith("> (선생님)")).allMatch(line -> line.endsWith("…")), text);
    }

    @Test
    void digestListsPartnershipInquiriesWithoutContacts() {
        List<Partnership> partnerships = List.of(new Partnership("햇살유치원", "원장", "협업하고 싶어요 010-1111-2222로 연락 주세요"));
        DiscordMessages.Window window = new DiscordMessages.Window(LocalTime.of(14, 0), LocalTime.of(14, 30),
                new Surveys(0, null, null), 0, 0, new JoinRequests(0, 0), signups(0, 0, 0), List.of(), partnerships);

        String text = DiscordMessages.newsDigest(window, null);

        assertFalse(window.empty());
        assertEquals("**[사용자 반응]** 14:00~14:30\n"
                + "협업 문의 1건\n"
                + "> (햇살유치원 · 원장) 협업하고 싶어요 (전화번호)로 연락 주세요", text);
    }
}
