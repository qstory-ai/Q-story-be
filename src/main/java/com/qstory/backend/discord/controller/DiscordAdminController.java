package com.qstory.backend.discord.controller;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.AdminTokenGuard;
import com.qstory.backend.discord.service.DiscordChannel;
import com.qstory.backend.discord.service.DiscordMention;
import com.qstory.backend.discord.service.DiscordMessages;
import com.qstory.backend.discord.service.DiscordNotifier;
import com.qstory.backend.discord.service.DiscordReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 디스코드 알림 수동 실행(연결 확인용). X-Admin-Token(QSTORY_STORY_IMPORT_TOKEN)으로 막는다. 보낸 결과(채널별 true/false)만
 * 돌려주고 웹훅 주소는 돌려주지 않는다.
 */
@Tag(name = "Discord alerts (internal)", description = "X-Admin-Token protected manual Discord notifications")
@RestController
public class DiscordAdminController {

    private final AdminTokenGuard adminTokenGuard;
    private final DiscordNotifier notifier;
    private final DiscordReportService reports;

    public DiscordAdminController(
            AdminTokenGuard adminTokenGuard, DiscordNotifier notifier, DiscordReportService reports) {
        this.adminTokenGuard = adminTokenGuard;
        this.notifier = notifier;
        this.reports = reports;
    }

    @Operation(summary = "Send a connection test message to each Discord channel",
            description = "#지표 @운영, #배포 @개발, #소식 @운영 @개발. Returns sent=true/false per channel "
                    + "(false = webhook missing, kill switch off, or Discord refused).")
    @PostMapping("/v1/admin/discord/test")
    public Map<String, Object> test(HttpServletRequest request) {
        adminTokenGuard.require(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics", notifier.sendNow(DiscordChannel.METRICS, DiscordMessages.connectionTest("#지표"),
                List.of(DiscordMention.OPS)));
        body.put("deploy", notifier.sendNow(DiscordChannel.DEPLOY, DiscordMessages.connectionTest("#배포"),
                List.of(DiscordMention.DEV)));
        body.put("news", notifier.sendNow(DiscordChannel.NEWS, DiscordMessages.connectionTest("#소식"),
                List.of(DiscordMention.OPS, DiscordMention.DEV)));
        return body;
    }

    @Operation(summary = "Send the morning metrics summary now",
            description = "Sends the summary for `date` (Asia/Seoul, yyyy-MM-dd, default yesterday) to #지표 regardless of "
                    + "whether today's scheduled summary already went out.")
    @PostMapping("/v1/admin/discord/daily-metrics")
    public Map<String, Object> dailyMetrics(
            HttpServletRequest request, @RequestParam(value = "date", required = false) String date) {
        adminTokenGuard.require(request);
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? reports.yesterday() : LocalDate.parse(date);
        } catch (DateTimeParseException invalid) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "date must be yyyy-MM-dd");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", day.toString());
        body.put("sent", reports.sendDailyMetricsNow(day));
        return body;
    }
}
