package com.qstory.backend.provider.openrouter.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qstory.backend.story.CompanionPersona;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 컴패니언 챗 시스템 프롬프트의 캐릭터 부분이 Java 문자열이 아니라 story_persona(personas.yaml)에서
 * 오는지 고정한다 - 페르소나가 personas.yaml 한 곳에서만 관리된다는 약속의 회귀 테스트.
 */
class CompanionPersonaPromptTest {

    private static CompanionPersona gretel() {
        return new CompanionPersona(
                "GRETEL", "HG-SPK-GRETEL", "character", "6~8세 또래 소녀",
                List.of("관찰력 있음", "다정함"), "무서워도 오빠와 함께 방법을 찾는 동생",
                List.of("~야", "~지"), List.of("오빠, 저기 봐"), "SHORT",
                List.of("두려움", "호기심"), "공황·절규 표현 금지",
                List.of("숲에 두 번 버려졌다는 것"),
                List.of("노파가 처음부터 마녀로 위장하고 있었다는 사실(F06 이전)"),
                List.of("과자집의 진짜 정체(F06 이전)"));
    }

    @Test
    void personaSheetFieldsAllReachThePrompt() {
        String joined = String.join(" ", OpenRouterClient.personaLines(gretel()));

        assertTrue(joined.contains("GRETEL"));
        assertTrue(joined.contains("6~8세 또래 소녀"));
        assertTrue(joined.contains("무서워도 오빠와 함께 방법을 찾는 동생"));
        assertTrue(joined.contains("관찰력 있음, 다정함"));
        assertTrue(joined.contains("~야, ~지"));
        assertTrue(joined.contains("오빠, 저기 봐"));
        assertTrue(joined.contains("10~20자"), "SHORT 성향이 문장 길이 힌트로 번역된다");
        assertTrue(joined.contains("두려움, 호기심"));
        assertTrue(joined.contains("공황·절규 표현 금지"));
        assertTrue(joined.contains("숲에 두 번 버려졌다는 것"));
        assertTrue(joined.contains("노파가 처음부터 마녀로 위장하고 있었다는 사실"));
        assertTrue(joined.contains("과자집의 진짜 정체"));
    }

    @Test
    void emptyOptionalListsAreOmittedRatherThanRenderedBlank() {
        CompanionPersona narrator = new CompanionPersona(
                "NARRATOR", "HG-SPK-NARRATOR", "내레이터", "성인(내레이터)",
                List.of("차분함"), "이야기를 따뜻하게 들려주는 화자",
                List.of("~했어요"), List.of(), "MEDIUM",
                List.of(), "", List.of("이야기 전체 줄거리"), List.of(), List.of("결말"));

        List<String> lines = OpenRouterClient.personaLines(narrator);

        assertFalse(lines.stream().anyMatch(line -> line.startsWith("가끔 쓰는 말")));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("표현해도 되는 감정")));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("이 인물이 모르는 것")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("10~28자")));
    }

    @Test
    void missingSheetFallsBackToOneNeutralLineInsteadOfInventingATrait() {
        List<String> lines = OpenRouterClient.personaLines(null);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("페르소나 시트가 없으므로"));
    }
}
