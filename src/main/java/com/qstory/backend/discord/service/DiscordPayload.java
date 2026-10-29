package com.qstory.backend.discord.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.config.DiscordProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 웹훅 본문 조립. 메시지는 임베드 하나로 보낸다 - 첫 줄 {@code **[제목]** 부제}는 임베드 제목과 색(종류별), 나머지 줄은
 * 본문, 마지막 줄의 {@code <링크>}는 제목에 거는 링크가 된다. 메시지 문구(DiscordMessages 등)는 이 형식만 지키면 된다.
 *
 * <p>멘션은 임베드 안에서 울리지 않으므로 content에 둔다. 웹훅은 역할 이름으로 멘션할 수 없어 {@code <@&ROLE_ID>}를
 * 쓰고, allowed_mentions에는 그 역할만 연다(parse는 비워 둔다 - 본문에 우연히 든 @everyone·사용자 멘션이 울리지 않게).
 * ID가 설정되지 않은 역할은 조용히 뺀다.
 */
public final class DiscordPayload {

    /** 디스코드 한도 - 임베드 제목 256자, 본문 4096자. 넘으면 잘라서 보낸다. */
    static final int MAX_TITLE = 256;
    static final int MAX_DESCRIPTION = 4096;

    private static final Pattern HEADER = Pattern.compile("^\\*\\*\\[([^\\]]+)]\\*\\*\\s*(.*)$");
    private static final Pattern LINK = Pattern.compile("^<(https?://\\S+)>$");

    /** 제목 종류별 색 - 지표는 파랑, 사용자 소식은 초록, 돈·한도 경고는 주황, 봇은 회색. */
    private static final Map<String, Integer> COLORS = Map.of(
            "아침 지표", 0x3B82F6,
            "사용자 반응", 0x22C55E,
            "협업 문의", 0x22C55E,
            "한도", 0xF59E0B,
            "비용", 0xF59E0B,
            "봇", 0x6B7280);
    private static final int DEFAULT_COLOR = 0x5865F2;

    private DiscordPayload() {}

    public static List<String> roleIds(DiscordProperties properties, Collection<DiscordMention> mentions) {
        Set<String> ids = new LinkedHashSet<>();
        for (DiscordMention mention : mentions) {
            String id = switch (mention) {
                case OPS -> properties.roleOpsId();
                case DEV -> properties.roleDevId();
            };
            if (id != null && id.trim().matches("\\d{5,25}")) {
                ids.add(id.trim());
            }
        }
        return new ArrayList<>(ids);
    }

    public static String build(ObjectMapper objectMapper, String text, List<String> roleIds) {
        ObjectNode root = objectMapper.createObjectNode();
        if (!roleIds.isEmpty()) {
            StringBuilder content = new StringBuilder();
            for (String id : roleIds) {
                content.append(content.isEmpty() ? "" : " ").append("<@&").append(id).append(">");
            }
            root.put("content", content.toString());
        }
        root.putArray("embeds").add(embed(objectMapper, text));
        ObjectNode allowed = root.putObject("allowed_mentions");
        allowed.putArray("parse");
        ArrayNode roles = allowed.putArray("roles");
        roleIds.forEach(roles::add);
        return root.toString();
    }

    static ObjectNode embed(ObjectMapper objectMapper, String text) {
        List<String> lines = new ArrayList<>(List.of((text == null ? "" : text).split("\n", -1)));
        ObjectNode embed = objectMapper.createObjectNode();
        int color = DEFAULT_COLOR;
        Matcher header = lines.isEmpty() ? null : HEADER.matcher(lines.get(0));
        if (header != null && header.matches()) {
            String kind = header.group(1).trim();
            String subtitle = header.group(2).trim();
            embed.put("title", cut(subtitle.isEmpty() ? kind : kind + " · " + subtitle, MAX_TITLE));
            color = COLORS.getOrDefault(kind, DEFAULT_COLOR);
            lines.remove(0);
        }
        // 링크는 제목에만 걸린다 - 제목이 없으면 본문에 그대로 둔다.
        Matcher link = lines.isEmpty() || !embed.has("title") ? null : LINK.matcher(lines.get(lines.size() - 1).trim());
        if (link != null && link.matches()) {
            embed.put("url", link.group(1));
            lines.remove(lines.size() - 1);
        }
        String description = String.join("\n", lines).strip();
        if (!description.isEmpty() || !embed.has("title")) {
            // 제목도 본문도 없으면 디스코드가 빈 임베드를 거절하므로 한 칸이라도 넣는다.
            embed.put("description", cut(description.isEmpty() ? "(내용 없음)" : description, MAX_DESCRIPTION));
        }
        embed.put("color", color);
        return embed;
    }

    private static String cut(String value, int max) {
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }
}
