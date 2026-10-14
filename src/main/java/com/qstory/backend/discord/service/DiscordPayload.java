package com.qstory.backend.discord.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.config.DiscordProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 웹훅 본문 조립. 웹훅은 역할 이름으로 멘션할 수 없어 {@code <@&ROLE_ID>}를 본문 앞에 붙이고, allowed_mentions에는
 * 그 역할만 연다(parse는 비워 둔다 - 본문에 우연히 든 @everyone·사용자 멘션이 울리지 않게). ID가 설정되지 않은
 * 역할은 조용히 뺀다.
 */
public final class DiscordPayload {

    /** 디스코드 content 한도는 2000자 - 넘으면 잘라서 보낸다. */
    static final int MAX_CONTENT = 2000;

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
        StringBuilder content = new StringBuilder();
        for (String id : roleIds) {
            content.append("<@&").append(id).append("> ");
        }
        content.append(text);
        String body = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT - 1) + "…" : content.toString();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("content", body);
        ObjectNode allowed = root.putObject("allowed_mentions");
        allowed.putArray("parse");
        ArrayNode roles = allowed.putArray("roles");
        roleIds.forEach(roles::add);
        return root.toString();
    }
}
