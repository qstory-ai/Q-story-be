package com.qstory.backend.discord.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.DiscordProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 역할 멘션은 <@&ID> + allowed_mentions.roles, ID가 없으면 멘션 없이. parse는 늘 비운다(@everyone 차단). */
class DiscordPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    static DiscordProperties props(String ops, String dev) {
        return new DiscordProperties(true, "https://discord.test/m", "https://discord.test/d", "https://discord.test/n",
                ops, dev, true, true, true, null, null, 100, "80,95", 5.0);
    }

    @Test
    void mentionsConfiguredRolesAndAllowsOnlyThem() throws Exception {
        List<String> roles =
                DiscordPayload.roleIds(props("111111", "222222"), List.of(DiscordMention.OPS, DiscordMention.DEV));
        JsonNode body = objectMapper.readTree(DiscordPayload.build(objectMapper, "안녕 @everyone", roles));

        assertEquals("<@&111111> <@&222222> 안녕 @everyone", body.get("content").asText());
        assertEquals(0, body.at("/allowed_mentions/parse").size());
        assertEquals("111111", body.at("/allowed_mentions/roles/0").asText());
        assertEquals("222222", body.at("/allowed_mentions/roles/1").asText());
    }

    @Test
    void missingOrInvalidRoleIdSendsWithoutMention() throws Exception {
        List<String> roles =
                DiscordPayload.roleIds(props("", "not-a-number"), List.of(DiscordMention.OPS, DiscordMention.DEV));
        JsonNode body = objectMapper.readTree(DiscordPayload.build(objectMapper, "본문", roles));

        assertEquals("본문", body.get("content").asText());
        assertEquals(0, body.at("/allowed_mentions/roles").size());
        assertTrue(body.at("/allowed_mentions/parse").isArray());
    }

    @Test
    void longContentIsCutToDiscordLimit() throws Exception {
        JsonNode body = objectMapper.readTree(DiscordPayload.build(objectMapper, "가".repeat(3000), List.of()));
        assertEquals(DiscordPayload.MAX_CONTENT, body.get("content").asText().length());
    }
}
