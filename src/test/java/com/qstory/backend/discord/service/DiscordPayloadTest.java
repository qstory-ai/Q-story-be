package com.qstory.backend.discord.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.DiscordProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 메시지는 임베드 하나 - 첫 줄 **[제목]**은 제목·색, 마지막 줄 <링크>는 제목 링크. 역할 멘션은 content의 <@&ID> +
 * allowed_mentions.roles, ID가 없으면 멘션 없이. parse는 늘 비운다(@everyone 차단).
 */
class DiscordPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    static DiscordProperties props(String ops, String dev) {
        return new DiscordProperties(true, "https://discord.test/m", "https://discord.test/d", "https://discord.test/n",
                ops, dev, true, true, true, null, null, 100, "80,95", 5.0, null);
    }

    @Test
    void mentionsConfiguredRolesInContentAndAllowsOnlyThem() throws Exception {
        List<String> roles =
                DiscordPayload.roleIds(props("111111", "222222"), List.of(DiscordMention.OPS, DiscordMention.DEV));
        JsonNode body = objectMapper.readTree(DiscordPayload.build(objectMapper, "안녕 @everyone", roles));

        assertEquals("<@&111111> <@&222222>", body.get("content").asText());
        assertEquals("안녕 @everyone", body.at("/embeds/0/description").asText());
        assertEquals(0, body.at("/allowed_mentions/parse").size());
        assertEquals("111111", body.at("/allowed_mentions/roles/0").asText());
        assertEquals("222222", body.at("/allowed_mentions/roles/1").asText());
    }

    @Test
    void missingOrInvalidRoleIdSendsWithoutMention() throws Exception {
        List<String> roles =
                DiscordPayload.roleIds(props("", "not-a-number"), List.of(DiscordMention.OPS, DiscordMention.DEV));
        JsonNode body = objectMapper.readTree(DiscordPayload.build(objectMapper, "본문", roles));

        assertFalse(body.has("content"));
        assertEquals("본문", body.at("/embeds/0/description").asText());
        assertEquals(0, body.at("/allowed_mentions/roles").size());
        assertTrue(body.at("/allowed_mentions/parse").isArray());
    }

    @Test
    void headerBecomesTitleAndColorAndLastLinkBecomesUrl() throws Exception {
        String text = "**[아침 지표]** 10/28(화) (괄호는 그저께 대비)\n방문자 12 (+3)\n<https://grafana.test/d/x>";
        JsonNode embed = objectMapper.readTree(DiscordPayload.build(objectMapper, text, List.of())).at("/embeds/0");

        assertEquals("아침 지표 · 10/28(화) (괄호는 그저께 대비)", embed.get("title").asText());
        assertEquals(0x3B82F6, embed.get("color").asInt());
        assertEquals("https://grafana.test/d/x", embed.get("url").asText());
        assertEquals("방문자 12 (+3)", embed.get("description").asText());
    }

    @Test
    void headerOnlyMessageHasNoDescriptionAndUnknownKindGetsDefaultColor() throws Exception {
        JsonNode embed = objectMapper.readTree(
                DiscordPayload.build(objectMapper, "**[새 종류]** 한 줄 알림", List.of())).at("/embeds/0");

        assertEquals("새 종류 · 한 줄 알림", embed.get("title").asText());
        assertFalse(embed.has("description"));
        assertEquals(0x5865F2, embed.get("color").asInt());
    }

    @Test
    void linkStaysInBodyWhenThereIsNoTitle() throws Exception {
        JsonNode embed = objectMapper.readTree(
                DiscordPayload.build(objectMapper, "연결 테스트\n<https://admin.test>", List.of())).at("/embeds/0");

        assertFalse(embed.has("url"));
        assertEquals("연결 테스트\n<https://admin.test>", embed.get("description").asText());
    }

    @Test
    void longTextIsCutToDiscordLimits() throws Exception {
        JsonNode embed = objectMapper.readTree(DiscordPayload.build(objectMapper,
                "**[비용]** " + "가".repeat(300) + "\n" + "나".repeat(5000), List.of())).at("/embeds/0");

        assertEquals(DiscordPayload.MAX_TITLE, embed.get("title").asText().length());
        assertEquals(DiscordPayload.MAX_DESCRIPTION, embed.get("description").asText().length());
    }
}
