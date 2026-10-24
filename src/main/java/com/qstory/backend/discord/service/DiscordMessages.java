package com.qstory.backend.discord.service;

import com.qstory.backend.discord.repository.DiscordMetricsRepository.Feedback;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.Funnel;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.GaTraffic;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.JoinRequests;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.Surveys;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 디스코드 메시지 문장 조립(순수 함수). 숫자·별점·역할 이름만 넣는다 - 개인정보는 받지도 않는다.
 * 예외는 마이페이지 요청 미리보기 하나: 운영이 바로 연락할 수 있게 글 앞부분을 넣되, 이메일·전화번호는 가린다.
 */
public final class DiscordMessages {

    private DiscordMessages() {}

    /** 하루치 숫자. ga는 GA 집계가 없으면 null. */
    public record Day(
            LocalDate date, GaTraffic ga, Funnel funnel, Map<String, Long> signups, long launchNotifications,
            Surveys surveys) {}

    /** 30분 묶음 숫자. */
    public record Window(
            LocalTime from, LocalTime to, Surveys surveys, long launchNotifications, long organizations,
            JoinRequests joinRequests, Map<String, Long> signups, List<Feedback> feedback) {

        public boolean empty() {
            return surveys.count() == 0 && launchNotifications == 0 && organizations == 0
                    && joinRequests.created() == 0 && total(signups) == 0 && feedback.isEmpty();
        }
    }

    public static String dailyMetrics(Day today, Day before, String link) {
        List<String> lines = new ArrayList<>();
        lines.add("**[아침 지표]** " + label(today.date()) + " (괄호는 그저께 대비)");
        if (today.ga() != null) {
            GaTraffic b = before.ga();
            lines.add("방문자 " + num(today.ga().visitors(), b == null ? null : b.visitors())
                    + " · 세션 " + num(today.ga().sessions(), b == null ? null : b.sessions()));
            lines.add("QR 유입 방문자 " + num(today.ga().qrVisitors(), b == null ? null : b.qrVisitors())
                    + " · 세션 " + num(today.ga().qrSessions(), b == null ? null : b.qrSessions()));
        } else {
            lines.add("방문자·세션: GA 집계 없음");
        }
        lines.add("데모 시작 " + num(today.funnel().starts(), before.funnel().starts())
                + " · 이야기 완주 " + num(today.funnel().completions(), before.funnel().completions())
                + " · 리포트 열람 " + num(today.funnel().reports(), before.funnel().reports()));
        lines.add("가입 " + num(total(today.signups()), total(before.signups())) + roles(today.signups(), true));
        lines.add("출시 알림 신청 " + num(today.launchNotifications(), before.launchNotifications()));
        String survey = "체험 후기 " + num(today.surveys().count(), before.surveys().count());
        if (today.surveys().count() > 0) {
            survey += " · " + stars(today.surveys());
        }
        lines.add(survey);
        appendLink(lines, link);
        return String.join("\n", lines);
    }

    public static String newsDigest(Window window, String link) {
        List<String> lines = new ArrayList<>();
        lines.add("**[사용자 반응]** " + window.from() + "~" + window.to());
        if (window.surveys().count() > 0) {
            lines.add("체험 후기 " + window.surveys().count() + "건 (" + stars(window.surveys()) + ")");
        }
        if (window.launchNotifications() > 0) {
            lines.add("출시 알림 신청 " + window.launchNotifications() + "건");
        }
        appendFeedback(lines, window.feedback());
        if (window.organizations() > 0) {
            lines.add("새 기관 등록 " + window.organizations() + "곳");
        }
        if (window.joinRequests().created() > 0) {
            lines.add("선생님 소속 신청 " + window.joinRequests().created() + "건 (원장 수락 대기 총 "
                    + window.joinRequests().pending() + "건)");
        }
        long signups = total(window.signups());
        if (signups > 0) {
            lines.add("새 가입 " + signups + "명" + roles(window.signups(), false));
        }
        appendLink(lines, link);
        return String.join("\n", lines);
    }

    private static final int FEEDBACK_PREVIEWS = 5;
    private static final int FEEDBACK_PREVIEW_CHARS = 100;
    private static final String BUG_PREFIX = "[오류 제보]";

    /** 마이페이지 요청 - 건수(오류 제보/기능 제안)와 앞 5건 미리보기. */
    private static void appendFeedback(List<String> lines, List<Feedback> feedback) {
        if (feedback.isEmpty()) {
            return;
        }
        long bugs = feedback.stream().filter(f -> f.message() != null && f.message().startsWith(BUG_PREFIX)).count();
        lines.add("마이페이지 요청 " + feedback.size() + "건 (오류 제보 " + bugs + " · 기능 제안 "
                + (feedback.size() - bugs) + ")");
        feedback.stream().limit(FEEDBACK_PREVIEWS).forEach(f -> lines.add("> " + feedbackPreview(f)));
        if (feedback.size() > FEEDBACK_PREVIEWS) {
            lines.add("> 외 " + (feedback.size() - FEEDBACK_PREVIEWS) + "건");
        }
    }

    static String feedbackPreview(Feedback feedback) {
        String text = feedback.message() == null ? "" : feedback.message().replaceAll("\\s+", " ").trim();
        // 연락처는 가리고, 멘션(@everyone 등)은 울리지 않게 끊는다.
        text = text.replaceAll("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+", "(이메일)")
                .replaceAll("01[0-9][- .]?\\d{3,4}[- .]?\\d{4}", "(전화번호)")
                .replace("@", "@\u200b");
        if (text.length() > FEEDBACK_PREVIEW_CHARS) {
            text = text.substring(0, FEEDBACK_PREVIEW_CHARS) + "…";
        }
        String role = feedback.role() == null ? "" : roleLabel(feedback.role());
        return role.isEmpty() ? text : "(" + role + ") " + text;
    }

    public static String geminiTtsUsage(int used, int limit, int step) {
        return "**[한도]** Gemini TTS 오늘 " + used + "/" + limit + "회 사용 (" + step + "회 넘음). "
                + "미국 태평양 자정(한국 16~17시)에 초기화, 데모 실시간 음성과 같은 키라 대량 생성은 미뤄 주세요.";
    }

    public static String openRouterLowBalance(double balance, double threshold) {
        return String.format(Locale.ROOT, "**[비용]** OpenRouter 잔액 $%.2f - 기준 $%.2f 아래예요. 충전이 필요해요.",
                balance, threshold);
    }

    public static String connectionTest(String channelLabel) {
        return "알림 연결 테스트 (" + channelLabel + ") - 이 메시지가 보이면 연결됐어요.";
    }

    static String num(long value, Long before) {
        if (before == null) {
            return Long.toString(value);
        }
        long diff = value - before;
        String sign = diff > 0 ? "+" + diff : diff < 0 ? Long.toString(diff) : "±0";
        return value + " (" + sign + ")";
    }

    static String stars(Surveys surveys) {
        return "아이 몰입 ★" + oneDecimal(surveys.engagement()) + " · 리포트 도움 ★" + oneDecimal(surveys.helpfulness());
    }

    private static String oneDecimal(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String roles(Map<String, Long> signups, boolean includeZero) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : signups.entrySet()) {
            if (entry.getValue() > 0 || includeZero) {
                parts.add(roleLabel(entry.getKey()) + " " + entry.getValue());
            }
        }
        return parts.isEmpty() ? "" : " - " + String.join(" · ", parts);
    }

    static String roleLabel(String role) {
        return switch (role) {
            case "PARENT" -> "보호자";
            case "TUTOR" -> "선생님";
            case "DIRECTOR" -> "원장";
            default -> role;
        };
    }

    static long total(Map<String, Long> signups) {
        return signups.values().stream().mapToLong(Long::longValue).sum();
    }

    private static String label(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth() + "("
                + date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ")";
    }

    private static void appendLink(List<String> lines, String link) {
        if (link != null && !link.isBlank()) {
            lines.add("<" + link.trim() + ">");
        }
    }
}
