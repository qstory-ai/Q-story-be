package com.qstory.backend.story;

import java.util.List;

/**
 * 컴패니언 챗(인물과 대화)이 시스템 프롬프트를 만들 때 쓰는 페르소나 시트의 런타임 뷰.
 * 원본은 fe/q-story-web/content/stories/&lt;slug&gt;/personas.yaml이고, POST /v1/admin/stories/import가
 * story_persona 테이블에 넣은 것을 {@code CompanionPersonaRegistry}가 메모리에 올려 준다. 예전에는
 * 헨젤/그레텔 성격이 OpenRouterClient의 Java 문자열에 따로 적혀 있어 personas.yaml과 어긋날 수
 * 있었는데, 이제 이 레코드가 유일한 출처다.
 *
 * <p>{@code knows}/{@code doesNotKnow}/{@code neverRevealsFirst}는 캐릭터의 지식 경계 - 아이가 물어도
 * 스포일러를 먼저 말하지 않게 하는 근거(스토리 제작 규약 §2.3).
 */
public record CompanionPersona(
        String castTag,
        String speakerId,
        String role,
        String ageBand,
        List<String> traits,
        String oneLiner,
        List<String> speechEndings,
        List<String> catchphrases,
        String sentenceLengthBias,
        List<String> emotionAllowed,
        String emotionCapNote,
        List<String> knows,
        List<String> doesNotKnow,
        List<String> neverRevealsFirst) {}
