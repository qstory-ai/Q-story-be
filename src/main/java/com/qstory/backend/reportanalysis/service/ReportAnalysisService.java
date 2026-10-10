package com.qstory.backend.reportanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.story.ChildFacingTerms;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 한 회차의 대화로 부모 리포트의 "이번에 드러난 관심과 생각"과 "함께 이야기할 카드"를 만든다(Q-39).
 *
 * <p>세 단계 - (1) 관심·생각 추출(근거는 아이 말 seq만) → (2) 근거 확인: 코드 검사(근거가 실제 아이 말인지,
 * 성향 일반화 표현이 없는지) + LLM 검수(인용 말만으로 뒷받침되는지, 넘치면 고쳐 쓰기) → (3) 부모 설명·대화 카드.
 * 아이 말이 없거나 반 수업이면 개인 분석은 하지 않고, 읽은 질문 장면마다 "이 장면으로 나눌 수 있는 이야기"만 만든다.
 * 그림·아이 말·그레텔 답·실행 결과는 리포트가 저장값을 그대로 쓰므로 여기서 다시 만들지 않는다.
 *
 * <p>프롬프트는 scratchpad 시제품(세 번 돌려 고친 것)을 옮긴 것이다 - 바꾸면 PROMPT_VERSION을 올린다.
 */
@Service
public class ReportAnalysisService {

    public static final String PROMPT_VERSION = "q39-report-v2";

    static final Pattern TRAIT_WORDS = Pattern.compile(
            "성향|성격|기질|발달|능력|잘하는|뛰어난|타고난|좋아하는 아이|점수|향상|천재|똑똑|적극성|적극적|지혜로운|창의적|공감 능력|사고력|큰 호기심|친근감을 느끼|보여줘요|모습이 드러나");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-zÀ-ž]{4,}");
    private static final Set<String> NOT_PERSONAL = Set.of("TEACHER_RELAY");

    private static final String EXTRACT_SYSTEM = """
            너는 아동 동화 앱의 부모 리포트를 위해, 한 회차 대화 기록에서 아이가 드러낸 관심과 생각을 뽑는다.
            규칙:
            - 근거는 role=CHILD인 발화의 id만 쓴다. 캐릭터 말·시스템 기록은 근거가 아니다(맥락으로만 쓴다).
            - 한 번 물은 것은 "궁금해했다"까지만. 성향·성격·능력·발달·"좋아하는 아이" 같은 일반화는 쓰지 않는다.
            - 캐릭터가 먼저 제안한 것에 "응" 하고 동의한 말은 아이 스스로의 생각이 아니다 - signals에 AGREED_WITH_CHARACTER로 표시한다.
            - "몰라", 단순 대답처럼 관심·생각이 드러나지 않은 말은 notAnalyzed에 이유와 함께 둔다.
            - 같은 화제가 여러 발화에 이어지면 하나의 관찰로 묶고 근거 id를 모두 넣는다.
            - 한 발화에 여러 표현 종류가 있을 수 있다.
            - observation은 아이가 실제로 한 말의 범위만 쓴다. 캐릭터가 바꿔 말한 내용(예: "~하는 동안 내가 열쇠를 가져오자는 거지?")을 아이 생각에 덧붙이지 않는다.
            - 아이 제안 뒤 ACTION_CONFIRMED가 있으면 signals에 PROPOSED_ACTION을 넣는다.
            - 도움 예시를 보고 고른 행동(viaSuggestion=true)은 아이 스스로의 생각이 아니다 - 관찰로 만들지 않는다.
            - observation은 부모에게 보여 줄 한 문장, 해요체.
            - '노파'라는 말은 쓰지 않는다. 과자집 주인은 정체가 드러나기 전 장면이면 '할머니', 드러난 뒤 장면이면 '마녀'라고 쓴다.""";

    private static final String VERIFY_SYSTEM = """
            너는 부모 리포트 검수자다. 각 관찰이 인용된 아이 발화만으로 뒷받침되는지 본다.
            - supported: 관찰의 핵심(무엇을 물었는지/제안했는지/말했는지)이 인용 발화에 있으면 true. 세부가 조금 넘쳐도 핵심이 맞으면 true로 하고 overreach로 표시한다.
            - overreach: 발화보다 넓게 일반화했거나 아이가 말하지 않은 세부를 덧붙였으면 true.
            - overreach면 revisedObservation에 인용 발화 범위 안의 문장을 반드시 쓴다(해요체). 아니면 null.
            - supported=false는 인용 발화와 관찰이 다른 이야기일 때만.""";

    private static final String CARD_SYSTEM = """
            너는 부모가 아이와 동화 이야기를 이어가도록 돕는다. 검증된 관찰마다 카드 하나를 만든다.
            - headline: 관찰 한 문장(해요체).
            - explanation: 실제 대화에서 무엇이 드러났는지와 그렇게 본 근거(먼저 말했는지, 초대·도움 뒤였는지, 직전에 캐릭터가 무엇을 말했는지). 2~4문장, 해요체.
              관찰을 묘사만 하고 평가하지 않는다 - "적극적", "지혜로운", "창의적", "호기심이 크다", "~하는 모습을 보여줘요" 같은 칭찬·판정을 쓰지 않는다.
            - 아이 말과 캐릭터가 바꿔 말한 내용을 구분한다. 캐릭터가 덧붙인 설명을 아이 생각으로 쓰지 않는다.
            - 안전 수칙·교훈을 가르치는 질문(예: "어떻게 하는 게 가장 안전할까?")은 넣지 않는다.
            - acknowledge: 감정·경험 이야기면 먼저 받아주는 말(부모가 아이에게 하는 말, 반말). 아니면 null.
            - openingLine: 부모가 처음 꺼낼 말(아이에게 반말). 아이가 이미 충분히 답한 것은 다시 묻지 않는다.
            - followUps: 아이 반응에 따라 골라 쓸 질문 2~3개. type은 REASON(이유 살펴보기)|POSSIBILITY(다른 가능성)|EXPERIENCE(자기 경험)|LOOK_TOGETHER(함께 살펴보기) 중.
            - 성향·능력 판정, 교훈 강요, 정답 유도는 쓰지 않는다.
            - 인물 이름은 입력에 적힌 화자 그대로 쓴다(직전 말을 한 사람은 precedingCharacterSpeaker). 모르는 사실을 지어내지 않는다.
            - '노파'라는 말은 쓰지 않는다. 과자집 주인은 정체가 드러나기 전 장면이면 '할머니', 드러난 뒤 장면이면 '마녀'라고 쓴다.
            - 한국어로만 쓴다. avoid 목록이 있으면 그 문제를 고친다.""";

    private static final String COMMON_SYSTEM = """
            너는 부모·교사용 동화 대화 거리를 만든다. 아이 개인 발화는 없다 - 아이의 관심을 추측하지 말고 "이 장면으로 나눌 수 있는 이야기"만 만든다.
            주어진 장면마다: openingLine(처음 꺼낼 말, 아이에게 반말) 1개와 followUps 4개(type: RECALL 이야기 돌아보기, REASON 이유 살펴보기, POSSIBILITY 다른 가능성, EXPERIENCE 자기 경험 각 1개).
            읽지 않은 장면 내용은 쓰지 않는다. 교훈 강요·정답 유도 없이.
            '노파'라는 말은 쓰지 않는다. 과자집 주인은 정체가 드러나기 전 장면이면 '할머니', 드러난 뒤 장면이면 '마녀'라고 쓴다.
            actualPaths에 그 장면에서 실제로 진행된 행동이 있으면 그 길을 기준으로 쓴다(기본 줄거리가 아니라). 한국어로만.""";

    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;
    private final String modelId;

    public ReportAnalysisService(
            OpenRouterClient openRouterClient, ObjectMapper objectMapper,
            @Value("${qstory.providers.openrouter.llm-model:google/gemini-3.6-flash}") String modelId) {
        this.openRouterClient = openRouterClient;
        this.objectMapper = objectMapper;
        this.modelId = modelId;
    }

    public String modelId() {
        return modelId;
    }

    /** 분석할 회차. turns는 PlaySessionService.listTurns 모양, content는 GET /v1/stories/{id}/content와 같은 꾸러미. */
    public record Input(
            String sessionKind, List<Map<String, Object>> turns, JsonNode content,
            String readFromSceneId, String readThroughSceneId) {}

    /** {observations, cards, commonScenes, notAnalyzed, rejected}. 할 게 없으면 빈 배열들. */
    public Map<String, Object> analyze(Input input) {
        StoryView story = StoryView.of(input.content());
        List<String> readScenes = story.readScenes(input.readFromSceneId(), input.readThroughSceneId(), input.turns());
        Map<String, Map<String, Object>> turnsById = new LinkedHashMap<>();
        for (Map<String, Object> turn : input.turns()) turnsById.put("t" + turn.get("seq"), turn);
        List<String> personalTurnIds = turnsById.entrySet().stream()
                .filter(e -> "CHILD".equals(e.getValue().get("role")))
                .filter(e -> !NOT_PERSONAL.contains(String.valueOf(e.getValue().get("speaker"))))
                .filter(e -> e.getValue().get("text") != null)
                .map(Map.Entry::getKey)
                .toList();

        List<Map<String, Object>> observations = new ArrayList<>();
        List<Map<String, Object>> cards = new ArrayList<>();
        List<Map<String, Object>> notAnalyzed = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        ObjectNode storyContext = story.context(readScenes);

        if ("HOME".equals(input.sessionKind()) && !personalTurnIds.isEmpty()) {
            ObjectNode extractPayload = objectMapper.createObjectNode();
            extractPayload.set("story", storyContext);
            extractPayload.set("turns", turnsForPrompt(turnsById));
            JsonNode extracted = call(EXTRACT_SYSTEM, extractPayload, extractSchema(), "q39_extract", 2500);
            for (JsonNode n : extracted.path("notAnalyzed")) {
                notAnalyzed.add(Map.of("seq", seqOf(n.path("turnId").asText()), "reason", n.path("reason").asText()));
            }
            List<JsonNode> candidates = new ArrayList<>();
            for (JsonNode o : extracted.path("observations")) {
                List<String> problems = codeCheck(o, turnsById);
                if (problems.isEmpty()) candidates.add(o);
                else rejected.add(Map.of("observation", o.path("observation").asText(), "stage", "code", "problems", problems));
            }
            List<JsonNode> verified = verify(candidates, turnsById, rejected);
            if (!verified.isEmpty()) {
                observations = verified.stream().map(o -> observationOut(o)).toList();
                cards = cards(storyContext, verified, turnsById);
            }
        }

        // 개인 관찰이 없는 질문 장면 + 반·개별 수업 기록은 장면 공통 대화 거리.
        Set<String> covered = new LinkedHashSet<>();
        for (Map<String, Object> o : observations) covered.add(String.valueOf(o.get("sceneId")));
        List<String> commonFor = story.anchorScenes().stream()
                .filter(readScenes::contains)
                .filter(sceneId -> !"HOME".equals(input.sessionKind()) || !covered.contains(sceneId))
                .toList();
        List<Map<String, Object>> commonScenes = new ArrayList<>();
        if (!commonFor.isEmpty()) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("story", storyContext);
            payload.set("sceneIds", objectMapper.valueToTree(commonFor));
            payload.set("actualPaths", actualPaths(input.turns(), story));
            JsonNode common = call(COMMON_SYSTEM, payload, commonSchema(), "q39_common", 2500);
            for (JsonNode scene : common.path("scenes")) {
                if (!commonFor.contains(scene.path("sceneId").asText())) continue;
                commonScenes.add(objectMapper.convertValue(scene, Map.class));
            }
        }

        // 프롬프트를 어겨도 부모·아이에게 보이는 글에 "노파"가 남지 않게 장면마다 호칭을 한 번 더 바꾼다.
        Map<String, String> sceneByKey = new LinkedHashMap<>();
        for (Map<String, Object> o : observations) sceneByKey.put(String.valueOf(o.get("key")), String.valueOf(o.get("sceneId")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("observations", renamedByScene(observations, o -> String.valueOf(o.get("sceneId"))));
        result.put("cards", renamedByScene(cards, card -> sceneByKey.get(String.valueOf(card.get("key")))));
        result.put("commonScenes", renamedByScene(commonScenes, scene -> String.valueOf(scene.get("sceneId"))));
        result.put("notAnalyzed", notAnalyzed);
        result.put("rejected", rejected);
        return result;
    }

    static List<Map<String, Object>> renamedByScene(
            List<Map<String, Object>> items, java.util.function.Function<Map<String, Object>, String> sceneOf) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : items) {
            ChildFacingTerms terms = ChildFacingTerms.forSceneId(sceneOf.apply(item));
            @SuppressWarnings("unchecked")
            Map<String, Object> renamed = (Map<String, Object>) renamed(item, terms);
            out.add(renamed);
        }
        return out;
    }

    /** 문자열 값만 바꾼다 - 키·id(sceneId 등)는 "노파"를 담지 않으므로 그대로 둬도 같다. */
    private static Object renamed(Object value, ChildFacingTerms terms) {
        if (value instanceof String text) return terms.apply(text);
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((key, child) -> out.put(key, renamed(child, terms)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(child -> renamed(child, terms)).toList();
        return value;
    }

    // ── 단계 ───────────────────────────────────────────────────────────────

    private List<JsonNode> verify(List<JsonNode> candidates, Map<String, Map<String, Object>> turnsById, List<Map<String, Object>> rejected) {
        if (candidates.isEmpty()) return List.of();
        ArrayNode items = objectMapper.createArrayNode();
        for (JsonNode o : candidates) {
            ObjectNode item = items.addObject();
            item.put("key", o.path("key").asText());
            item.put("observation", o.path("observation").asText());
            ArrayNode evidence = item.putArray("evidence");
            for (JsonNode id : o.path("evidenceTurnIds")) evidence.add(String.valueOf(turnsById.get(id.asText()).get("text")));
            String preceding = o.path("precedingCharacterTurnId").asText(null);
            Object line = preceding == null || !turnsById.containsKey(preceding) ? null : turnsById.get(preceding).get("text");
            if (line == null) item.putNull("precedingCharacterLine");
            else item.put("precedingCharacterLine", String.valueOf(line));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("observations", items);
        JsonNode verdicts = call(VERIFY_SYSTEM, payload, verifySchema(), "q39_verify", 1500);
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        for (JsonNode v : verdicts.path("results")) byKey.put(v.path("key").asText(), v);
        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode o : candidates) {
            JsonNode v = byKey.get(o.path("key").asText());
            if (v == null || !v.path("supported").asBoolean(false)) {
                rejected.add(Map.of("observation", o.path("observation").asText(), "stage", "verify",
                        "problems", List.of(v == null ? "검수 결과 없음" : v.path("reason").asText())));
                continue;
            }
            ObjectNode copy = o.deepCopy();
            if (v.path("overreach").asBoolean(false)) {
                String revised = v.path("revisedObservation").asText(null);
                if (revised == null || revised.isBlank() || TRAIT_WORDS.matcher(revised).find()) {
                    rejected.add(Map.of("observation", o.path("observation").asText(), "stage", "verify",
                            "problems", List.of("일반화를 고치지 못함")));
                    continue;
                }
                copy.put("observation", revised);
            }
            kept.add(copy);
        }
        return kept;
    }

    private List<Map<String, Object>> cards(ObjectNode storyContext, List<JsonNode> observations, Map<String, Map<String, Object>> turnsById) {
        ArrayNode items = objectMapper.createArrayNode();
        for (JsonNode o : observations) {
            ObjectNode item = items.addObject();
            item.put("key", o.path("key").asText());
            item.put("observation", o.path("observation").asText());
            item.put("sceneId", o.path("sceneId").asText());
            ArrayNode evidence = item.putArray("evidence");
            int lastSeq = 0;
            for (JsonNode id : o.path("evidenceTurnIds")) {
                Map<String, Object> turn = turnsById.get(id.asText());
                ObjectNode e = evidence.addObject();
                e.put("text", String.valueOf(turn.get("text")));
                e.put("inputMode", String.valueOf(turn.getOrDefault("inputMode", "TEXT")));
                lastSeq = Math.max(lastSeq, (Integer) turn.get("seq"));
            }
            String preceding = o.path("precedingCharacterTurnId").asText(null);
            Map<String, Object> precedingTurn = preceding == null ? null : turnsById.get(preceding);
            if (precedingTurn == null) {
                item.putNull("precedingCharacterLine");
                item.putNull("precedingCharacterSpeaker");
            } else {
                item.put("precedingCharacterLine", String.valueOf(precedingTurn.get("text")));
                item.put("precedingCharacterSpeaker", "그레텔");
            }
            item.put("initiative", o.path("initiative").asText());
            item.set("expressionTypes", o.path("expressionTypes"));
            item.set("signals", o.path("signals"));
            ArrayNode later = item.putArray("laterDialogue");
            final int after = lastSeq;
            String sceneId = o.path("sceneId").asText();
            turnsById.values().stream()
                    .filter(t -> sceneId.equals(t.get("sceneId")) && (Integer) t.get("seq") > after)
                    .limit(6)
                    .forEach(t -> later.add(t.get("role") + ": " + (t.get("text") != null ? t.get("text") : t.get("event"))));
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("story", storyContext);
        payload.set("observations", items);
        JsonNode generated = call(CARD_SYSTEM, payload, cardSchema(), "q39_cards", 3000);
        List<String> problems = textProblems(generated);
        if (!problems.isEmpty()) {
            payload.set("avoid", objectMapper.valueToTree(problems));
            generated = call(CARD_SYSTEM, payload, cardSchema(), "q39_cards", 3000);
            if (!textProblems(generated).isEmpty()) {
                throw new IllegalStateException("cards still have problems: " + textProblems(generated));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode card : generated.path("cards")) out.add(objectMapper.convertValue(card, Map.class));
        return out;
    }

    // ── 검사 ───────────────────────────────────────────────────────────────

    static List<String> codeCheck(JsonNode observation, Map<String, Map<String, Object>> turnsById) {
        List<String> problems = new ArrayList<>();
        if (!observation.path("evidenceTurnIds").isArray() || observation.path("evidenceTurnIds").isEmpty()) {
            problems.add("근거 발화 없음");
        }
        for (JsonNode id : observation.path("evidenceTurnIds")) {
            Map<String, Object> turn = turnsById.get(id.asText());
            if (turn == null) problems.add("없는 발화 " + id.asText());
            else if (!"CHILD".equals(turn.get("role"))) problems.add(id.asText() + "는 아이 발화가 아님");
            else if (NOT_PERSONAL.contains(String.valueOf(turn.get("speaker")))) problems.add(id.asText() + "는 개인 발화가 아님");
            else if (turn.get("text") == null) problems.add(id.asText() + "는 원문이 없음");
        }
        if (TRAIT_WORDS.matcher(observation.path("observation").asText("")).find()) problems.add("성향·능력 일반화 표현");
        return problems;
    }

    /** 생성 글의 값들에서 외국어 단어 섞임과 성향 일반화를 찾는다(키·type 값은 빼고). */
    static List<String> textProblems(JsonNode generated) {
        StringBuilder text = new StringBuilder();
        collectText(generated, text);
        List<String> problems = new ArrayList<>();
        var latin = LATIN_WORD.matcher(text);
        Set<String> words = new LinkedHashSet<>();
        while (latin.find()) words.add(latin.group());
        if (!words.isEmpty()) problems.add("외국어 섞임: " + String.join(", ", words));
        if (TRAIT_WORDS.matcher(text).find()) problems.add("성향·능력 일반화 표현");
        return problems;
    }

    private static void collectText(JsonNode node, StringBuilder out) {
        if (node.isTextual()) {
            out.append(node.asText()).append('\n');
        } else if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (!Set.of("key", "type", "sceneId").contains(field.getKey())) collectText(field.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectText(child, out));
        }
    }

    // ── 입력·출력 모양 ─────────────────────────────────────────────────────

    private ArrayNode turnsForPrompt(Map<String, Map<String, Object>> turnsById) {
        ArrayNode out = objectMapper.createArrayNode();
        turnsById.forEach((id, turn) -> {
            ObjectNode t = out.addObject();
            t.put("id", id);
            for (String key : List.of("sceneId", "anchorId", "entryMode", "role", "speaker", "text", "inputMode",
                    "helpStep", "event", "familyId", "viaSuggestion", "suggestionLabel", "proposedFamilyId")) {
                Object value = turn.get(key);
                if (value != null) t.set(key, objectMapper.valueToTree(value));
            }
        });
        return out;
    }

    private Map<String, Object> observationOut(JsonNode o) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", o.path("key").asText());
        out.put("sceneId", o.path("sceneId").asText());
        out.put("anchorId", o.path("anchorId").isTextual() ? o.path("anchorId").asText() : null);
        List<Integer> seqs = new ArrayList<>();
        for (JsonNode id : o.path("evidenceTurnIds")) seqs.add(seqOf(id.asText()));
        out.put("evidenceSeqs", seqs);
        out.put("expressionTypes", objectMapper.convertValue(o.path("expressionTypes"), List.class));
        out.put("signals", objectMapper.convertValue(o.path("signals"), List.class));
        out.put("initiative", o.path("initiative").asText());
        out.put("observation", o.path("observation").asText());
        return out;
    }

    private ArrayNode actualPaths(List<Map<String, Object>> turns, StoryView story) {
        ArrayNode out = objectMapper.createArrayNode();
        for (Map<String, Object> turn : turns) {
            if (!"ACTION_CONFIRMED".equals(turn.get("event"))) continue;
            ObjectNode path = out.addObject();
            path.put("sceneId", String.valueOf(turn.get("sceneId")));
            path.put("whatHappened", story.familyMeaning(String.valueOf(turn.get("familyId"))));
            path.put("chosenFromExample", Boolean.TRUE.equals(turn.get("viaSuggestion")));
        }
        return out;
    }

    private static int seqOf(String turnId) {
        try {
            return Integer.parseInt(turnId.replaceFirst("^t", ""));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private JsonNode call(String system, ObjectNode payload, ObjectNode schema, String name, int maxTokens) {
        return openRouterClient.generateStructuredCompletion(
                modelId, system, List.of(), payload.toString(), schema, name, maxTokens, 0.2,
                ProviderErrorCode.OPENROUTER_RESPONSE_FAILED, "리포트 분석을 만들지 못했어요.",
                RequestDeadline.startingNow(90_000));
    }

    // ── 스키마(strict) ─────────────────────────────────────────────────────

    private ObjectNode obj(String... required) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putObject("properties");
        ArrayNode req = schema.putArray("required");
        for (String key : required) req.add(key);
        return schema;
    }

    private ObjectNode str() {
        ObjectNode s = objectMapper.createObjectNode();
        s.put("type", "string");
        return s;
    }

    private ObjectNode nullableStr() {
        ObjectNode s = objectMapper.createObjectNode();
        s.putArray("type").add("string").add("null");
        return s;
    }

    private ObjectNode bool() {
        ObjectNode s = objectMapper.createObjectNode();
        s.put("type", "boolean");
        return s;
    }

    private ObjectNode enumOf(String... values) {
        ObjectNode s = str();
        ArrayNode e = s.putArray("enum");
        for (String v : values) e.add(v);
        return s;
    }

    private ObjectNode arrayOf(ObjectNode items) {
        ObjectNode s = objectMapper.createObjectNode();
        s.put("type", "array");
        s.set("items", items);
        return s;
    }

    private ObjectNode extractSchema() {
        ObjectNode observation = obj("key", "sceneId", "anchorId", "evidenceTurnIds", "precedingCharacterTurnId",
                "expressionTypes", "signals", "initiative", "topic", "observation");
        ObjectNode p = (ObjectNode) observation.get("properties");
        p.set("key", str());
        p.set("sceneId", str());
        p.set("anchorId", nullableStr());
        p.set("evidenceTurnIds", arrayOf(str()));
        p.set("precedingCharacterTurnId", nullableStr());
        p.set("expressionTypes", arrayOf(enumOf("QUESTION", "PROPOSAL", "EXPERIENCE", "EMOTION", "PREFERENCE", "OPINION")));
        p.set("signals", arrayOf(enumOf("SINGLE_QUESTION", "FOLLOW_UP_SAME_TOPIC", "DIRECT_PREFERENCE",
                "PERSONAL_EXPERIENCE", "PROPOSED_ACTION", "AGREED_WITH_CHARACTER", "EMOTION_EXPRESSED")));
        p.set("initiative", enumOf("SPONTANEOUS", "INVITE", "AFTER_HELP"));
        p.set("topic", str());
        p.set("observation", str());
        ObjectNode skipped = obj("turnId", "reason");
        ((ObjectNode) skipped.get("properties")).set("turnId", str());
        ((ObjectNode) skipped.get("properties")).set("reason", str());
        ObjectNode root = obj("observations", "notAnalyzed");
        ((ObjectNode) root.get("properties")).set("observations", arrayOf(observation));
        ((ObjectNode) root.get("properties")).set("notAnalyzed", arrayOf(skipped));
        return root;
    }

    private ObjectNode verifySchema() {
        ObjectNode result = obj("key", "supported", "overreach", "reason", "revisedObservation");
        ObjectNode p = (ObjectNode) result.get("properties");
        p.set("key", str());
        p.set("supported", bool());
        p.set("overreach", bool());
        p.set("reason", str());
        p.set("revisedObservation", nullableStr());
        ObjectNode root = obj("results");
        ((ObjectNode) root.get("properties")).set("results", arrayOf(result));
        return root;
    }

    private ObjectNode followUp(String... types) {
        ObjectNode f = obj("type", "text");
        ((ObjectNode) f.get("properties")).set("type", enumOf(types));
        ((ObjectNode) f.get("properties")).set("text", str());
        return f;
    }

    private ObjectNode cardSchema() {
        ObjectNode card = obj("key", "headline", "explanation", "acknowledge", "openingLine", "followUps");
        ObjectNode p = (ObjectNode) card.get("properties");
        p.set("key", str());
        p.set("headline", str());
        p.set("explanation", str());
        p.set("acknowledge", nullableStr());
        p.set("openingLine", str());
        p.set("followUps", arrayOf(followUp("REASON", "POSSIBILITY", "EXPERIENCE", "LOOK_TOGETHER")));
        ObjectNode root = obj("cards");
        ((ObjectNode) root.get("properties")).set("cards", arrayOf(card));
        return root;
    }

    private ObjectNode commonSchema() {
        ObjectNode scene = obj("sceneId", "openingLine", "followUps");
        ObjectNode p = (ObjectNode) scene.get("properties");
        p.set("sceneId", str());
        p.set("openingLine", str());
        p.set("followUps", arrayOf(followUp("RECALL", "REASON", "POSSIBILITY", "EXPERIENCE")));
        ObjectNode root = obj("scenes");
        ((ObjectNode) root.get("properties")).set("scenes", arrayOf(scene));
        return root;
    }
}
