package com.qstory.backend.reportanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.storyreport.SessionKind;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 한 회차의 대화로 부모 리포트의 "이번에 드러난 관심과 생각"과 "함께 이야기할 카드"를 만든다(Q-39).
 *
 * <p>세 단계 - (1) 관심·생각 추출(근거는 아이 말 seq만) → (2) 근거 확인: 코드 검사(근거가 실제 아이 말인지,
 * 성향 일반화 표현이 없는지) + LLM 검수(인용 말만으로 뒷받침되는지, 넘치면 고쳐 쓰기) → (3) 부모 설명·대화 카드.
 * 아이 말이 없거나 반 수업이면 개인 분석은 하지 않고, 읽은 질문 장면마다 "이 장면으로 나눌 수 있는 이야기"만 만든다.
 * 그림·아이 말·그레텔 답·실행 결과는 리포트가 저장값을 그대로 쓰므로 여기서 다시 만들지 않는다.
 *
 * <p>가정 회차는 두 단계가 더 붙는다(노션 "[초안 v1] 부모 보고서 개편 · 개인화").
 * (4) 아이 말마다 질문 여부·수준(Glaubman 1~4)·종류를 LLM이 한 번에 분류하고 코드가 회차 숫자로 센다(QuestionStats).
 * (5) 같은 아이의 지난 회차 숫자(history)와 오늘 질문을 주고 "오늘 한 줄·지난번과 달라진 점·관심이 머문 곳·오늘 밤 이어갈
 * 질문"을 쓴다. 숫자 비교는 코드가 넘긴 값만 쓰고, 지난 회차가 없으면 비교 문장은 코드가 지운다.
 * (4)(5)가 실패해도 (1)~(3) 결과는 그대로 저장한다. 개인화 칸만 빈다.
 *
 * <p>프롬프트는 scratchpad 시제품(세 번 돌려 고친 것)을 옮긴 것이다 - 바꾸면 PROMPT_VERSION을 올린다.
 */
@Service
public class ReportAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ReportAnalysisService.class);

    public static final String PROMPT_VERSION = "report-v4-personal";

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
            - event=TRY_FAILED는 아이가 낸 준비되지 않은 행동을 이야기 속에서 해 보는 시도 장면을 시작한 기록이고(text = 해 본 것), event=TRY_ENDED는 그 장면에서 몇 번 더 이야기한 뒤(attemptTurns) 원래 질문으로 돌아온 기록이다. 관찰은 "~을 해 보자고 했고, 잘 안 되자 다른 방법을 생각했다"까지만 쓰고, 실패를 아이의 잘못·성향·나쁜 생각으로 쓰지 않는다. 시도 장면 안의 아이 말도 아이 생각의 근거가 된다.
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

    private static final String CLASSIFY_SYSTEM = """
            너는 아동 질문 연구의 코더다. 동화를 읽으며 아이가 한 말 하나하나를 분류한다.
            targetTurnIds의 발화마다 결과를 하나씩 낸다. 다른 발화(캐릭터 말, 시스템 기록)는 앞뒤 맥락으로만 본다.
            kind:
            - QUESTION: 모르는 것을 알려고 묻는 말. 이야기 속 인물이나 상황, 낱말 뜻을 묻는 것.
            - PROPOSAL: 행동 제안. "열쇠 가져오자", "문 열어 볼까?"처럼 물음표가 있어도 뭔가 하자는 말이면 여기.
            - OTHER: 캐릭터 물음에 대한 대답, 인사, 감탄, 이야기 밖 요청("화장실 가도 돼?"), 알아들을 수 없는 말.
            level(QUESTION일 때만 1~4, 아니면 0):
            - 1 예/아니오로 답할 수 있는 질문. 예: "마녀야?", "집에 갈 수 있어?"
            - 2 사실을 묻는 질문. 누가, 무엇, 어디, 언제, 몇. 예: "이거 누구 집이야?", "새는 어디 가?"
            - 3 이유나 방법을 묻는 질문. 왜, 어떻게. 예: "왜 과자로 집을 만들었어?", "어떻게 나가?"
            - 4 가설이나 예측을 묻는 질문. 만약, 앞으로 어떻게 될까. 예: "마녀가 깨면 어떻게 돼?", "빵 부스러기가 없었으면?"
            - 형식이 예/아니오여도 이유를 짐작해 확인하는 말("배고파서 그런 거야?")은 3, 앞일을 짐작해 확인하는 말("그럼 잡히는 거야?")은 4.
            category(QUESTION일 때만, 아니면 NONE):
            - NARRATIVE: 다음에 무슨 일이 생기는지, 줄거리 전개
            - FACT_CAUSE: 사물·세상의 사실이나 원인(과자집은 왜 안 녹아?, 새는 왜 빵을 먹어?)
            - MIND_RELATION: 인물의 마음, 생각, 관계(헨젤은 무서웠어?, 엄마는 왜 그랬어?)
            followUp: 바로 앞 캐릭터 답을 듣고 같은 화제를 이어서 다시 물은 질문이면 true.
            topic: QUESTION이면 무엇에 대한 질문인지 2~6글자 명사구(예: "마녀의 집", "새", "엄마의 마음"). 아니면 빈 문자열.
            reason: 그렇게 분류한 이유 한 줄. 연구자용이다.
            '노파'라는 말은 쓰지 않는다. 과자집 주인은 정체가 드러나기 전 장면이면 '할머니', 드러난 뒤 장면이면 '마녀'라고 쓴다.""";

    private static final String PERSONAL_SYSTEM = """
            너는 동화 앱의 부모 리포트 맨 위 개인화 문장을 쓴다. 부모가 "우리 아이가 오늘 뭘 물었고 지난번과 뭐가 달랐는지"를
            한눈에 보게 한다. 모든 문장은 해요체, 한국어로만.
            - childName이 있으면 이름으로 부른다(예: "하은이는"). 없으면 "아이는".
            - todayLine: 오늘 회차 한 문장. 이야기 제목과 오늘 질문 수(today.summary.questions)를 사실대로. 질문이 0이면 질문 이야기는 하지 않고
              어떻게 함께했는지(대답, 제안)만 쓴다.
            - changeLine: history(같은 아이의 지난 회차, 최근 것부터)와 오늘을 비교해 눈에 띄는 차이 하나를 한두 문장으로. history가 비었거나
              차이가 작으면 null. 예: "지난 두 번은 질문이 없었는데 오늘은 처음으로 물어봤어요", "오늘은 처음으로 '왜'를 묻는 질문이 나왔어요".
              숫자는 입력에 있는 값만 쓴다. 늘었다, 좋아졌다처럼 효과나 발전으로 단정하지 않는다. 다른 아이나 평균과 비교하지 않는다.
            - interestLine: 오늘과 지난 회차의 topics, byCategory에서 여러 회차에 되풀이된 관심이 있으면 한 문장. 없거나 history가 비었으면 null.
              예: "세 번 읽는 동안 인물이 왜 그렇게 했는지를 자주 물었어요".
            - tonightQuestion: 오늘 아이가 한 질문(today.questions) 하나를 골라 부모가 오늘 밤 아이에게 되물을 말 한 개(반말).
              정답을 알려 주지 말고 아이 생각을 묻는다. 이유·예측 질문이 있으면 그것을 먼저 고른다. basedOnTurnId는 고른 질문의 turnId.
              오늘 질문이 없으면 null.
            - 성향·성격·능력·발달 판정, 칭찬("똑똑", "창의적"), 점수, 교훈, 부모가 할 일 지시를 쓰지 않는다.
            - 아이가 실제로 한 말 범위를 넘지 않는다. avoid 목록이 있으면 그 문제를 고친다.
            '노파'라는 말은 쓰지 않는다. 과자집 주인은 정체가 드러나기 전 장면이면 '할머니', 드러난 뒤 장면이면 '마녀'라고 쓴다.""";

    private final OpenRouterClient openRouterClient;
    private final ObjectMapper objectMapper;
    private final String modelId;

    public ReportAnalysisService(OpenRouterClient openRouterClient, ObjectMapper objectMapper, AppProperties config) {
        this.openRouterClient = openRouterClient;
        this.objectMapper = objectMapper;
        this.modelId = config.providers().openRouter().llmModel();
    }

    /** 설정된 기본 모델 - 분석이 실패해 실제로 답한 모델을 모를 때 기록에 쓴다. */
    public String modelId() {
        return modelId;
    }

    /** report_analysis.model_id(varchar 120)·demo_report_analysis.model_id(varchar 128) 중 짧은 쪽. */
    static final int MODEL_ID_MAX = 120;

    /**
     * 분석 결과와 실제로 답한 모델. 한 분석은 LLM을 여러 번 부르고 호출마다 예비 모델로 넘어갈 수 있어서, 답한 모델을
     * 부른 순서대로 중복 없이 쉼표로 이어 남긴다(예: "a,b"). LLM을 한 번도 부르지 않았으면 설정된 모델.
     */
    public record Analysis(Map<String, Object> result, String modelId) {}

    /** 분석할 회차. turns는 PlaySessionService.listTurns 모양, content는 GET /v1/stories/{id}/content와 같은 꾸러미. */
    public record Input(
            SessionKind sessionKind, List<Map<String, Object>> turns, JsonNode content,
            String readFromSceneId, String readThroughSceneId,
            String childName, Integer durationSeconds, List<PastSession> history) {

        /** 아이와 지난 회차를 모르는 경우(데모 회차). */
        public Input(SessionKind sessionKind, List<Map<String, Object>> turns, JsonNode content,
                String readFromSceneId, String readThroughSceneId) {
            this(sessionKind, turns, content, readFromSceneId, readThroughSceneId, null, null, List.of());
        }
    }

    /** 같은 아이의 지난 회차 하나. summary는 그 회차에 저장된 questionSummary(QuestionStats.summarize 모양). */
    public record PastSession(String storyTitle, long daysAgo, Map<String, Object> summary) {}

    /** {observations, cards, commonScenes, notAnalyzed, rejected}. 할 게 없으면 빈 배열들. */
    public Map<String, Object> analyze(Input input) {
        return analyzeWithModel(input).result();
    }

    /** analyze()와 같고, 실제로 답한 모델을 함께 돌려준다 - 저장할 때 model_id로 쓴다. */
    public Analysis analyzeWithModel(Input input) {
        Set<String> usedModels = new LinkedHashSet<>();
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

        if (input.sessionKind() == SessionKind.HOME && !personalTurnIds.isEmpty()) {
            ObjectNode extractPayload = objectMapper.createObjectNode();
            extractPayload.set("story", storyContext);
            extractPayload.set("turns", turnsForPrompt(turnsById));
            JsonNode extracted = call(usedModels, EXTRACT_SYSTEM, extractPayload, extractSchema(), "q39_extract", 2500);
            for (JsonNode n : extracted.path("notAnalyzed")) {
                notAnalyzed.add(Map.of("seq", seqOf(n.path("turnId").asText()), "reason", n.path("reason").asText()));
            }
            List<JsonNode> candidates = new ArrayList<>();
            for (JsonNode o : extracted.path("observations")) {
                List<String> problems = codeCheck(o, turnsById);
                if (problems.isEmpty()) candidates.add(o);
                else rejected.add(Map.of("observation", o.path("observation").asText(), "stage", "code", "problems", problems));
            }
            List<JsonNode> verified = verify(usedModels, candidates, turnsById, rejected);
            if (!verified.isEmpty()) {
                observations = verified.stream().map(o -> observationOut(o)).toList();
                cards = cards(usedModels, storyContext, verified, turnsById);
            }
        }

        // 질문 분류와 개인화 문장(가정 회차만). 실패해도 위 관찰·카드는 그대로 남긴다.
        List<Map<String, Object>> questions = List.of();
        Map<String, Object> questionSummary = null;
        Map<String, Object> personal = null;
        if (input.sessionKind() == SessionKind.HOME) {
            try {
                questions = personalTurnIds.isEmpty() ? List.of() : classify(usedModels, storyContext, turnsById, personalTurnIds);
                questionSummary = QuestionStats.summarize(questions);
                personal = personal(usedModels, input, story, questions, questionSummary, turnsById, personalTurnIds);
            } catch (RuntimeException error) {
                log.warn("report-analysis.personal-failed reason={}", error.toString());
            }
        }

        // 개인 관찰이 없는 질문 장면 + 반·개별 수업 기록은 장면 공통 대화 거리.
        Set<String> covered = new LinkedHashSet<>();
        for (Map<String, Object> o : observations) covered.add(String.valueOf(o.get("sceneId")));
        List<String> commonFor = story.anchorScenes().stream()
                .filter(readScenes::contains)
                .filter(sceneId -> input.sessionKind() != SessionKind.HOME || !covered.contains(sceneId))
                .toList();
        List<Map<String, Object>> commonScenes = new ArrayList<>();
        if (!commonFor.isEmpty()) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("story", storyContext);
            payload.set("sceneIds", objectMapper.valueToTree(commonFor));
            payload.set("actualPaths", actualPaths(input.turns(), story));
            JsonNode common = call(usedModels, COMMON_SYSTEM, payload, commonSchema(), "q39_common", 2500);
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
        result.put("questions", questions);
        result.put("questionSummary", questionSummary);
        result.put("personal", personal == null ? null
                : renamedByScene(List.of(personal), p -> lastScene(input.turns())).get(0));
        result.put("notAnalyzed", notAnalyzed);
        result.put("rejected", rejected);
        return new Analysis(result, joinedModelId(usedModels, modelId));
    }

    static String joinedModelId(Set<String> usedModels, String configured) {
        if (usedModels.isEmpty()) return configured;
        String joined = String.join(",", usedModels);
        // 칸보다 길면 저장(finish) 자체가 실패해 작업이 RUNNING에 남는다 - 잘라서라도 남긴다.
        return joined.length() <= MODEL_ID_MAX ? joined : joined.substring(0, MODEL_ID_MAX);
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

    private List<JsonNode> verify(Set<String> usedModels, List<JsonNode> candidates, Map<String, Map<String, Object>> turnsById, List<Map<String, Object>> rejected) {
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
        JsonNode verdicts = call(usedModels, VERIFY_SYSTEM, payload, verifySchema(), "q39_verify", 1500);
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

    private List<Map<String, Object>> cards(Set<String> usedModels, ObjectNode storyContext, List<JsonNode> observations, Map<String, Map<String, Object>> turnsById) {
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
        JsonNode generated = call(usedModels, CARD_SYSTEM, payload, cardSchema(), "q39_cards", 3000);
        List<String> problems = textProblems(generated);
        if (!problems.isEmpty()) {
            payload.set("avoid", objectMapper.valueToTree(problems));
            generated = call(usedModels, CARD_SYSTEM, payload, cardSchema(), "q39_cards", 3000);
            if (!textProblems(generated).isEmpty()) {
                throw new IllegalStateException("cards still have problems: " + textProblems(generated));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode card : generated.path("cards")) out.add(objectMapper.convertValue(card, Map.class));
        return out;
    }

    /** 아이 말마다 {seq, kind, level, category, followUp, topic, reason}. */
    private List<Map<String, Object>> classify(
            Set<String> usedModels, ObjectNode storyContext, Map<String, Map<String, Object>> turnsById, List<String> personalTurnIds) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("story", storyContext);
        payload.set("turns", turnsForPrompt(turnsById));
        payload.set("targetTurnIds", objectMapper.valueToTree(personalTurnIds));
        JsonNode classified = call(usedModels, CLASSIFY_SYSTEM, payload, classifySchema(), "report_classify", 4000);
        return classifiedOut(classified, personalTurnIds);
    }

    /** 개인 아이 말이 아닌 결과와 중복은 버리고, 질문이 아니면 수준·종류를 비운다. 순서는 대화 순서. */
    static List<Map<String, Object>> classifiedOut(JsonNode classified, List<String> personalTurnIds) {
        Map<String, Map<String, Object>> byTurn = new LinkedHashMap<>();
        for (JsonNode item : classified.path("items")) {
            String turnId = item.path("turnId").asText();
            if (!personalTurnIds.contains(turnId) || byTurn.containsKey(turnId)) continue;
            String kind = item.path("kind").asText("OTHER");
            boolean question = "QUESTION".equals(kind);
            int level = question ? Math.max(1, Math.min(4, item.path("level").asInt(1))) : 0;
            String category = question && QuestionStats.CATEGORIES.contains(item.path("category").asText())
                    ? item.path("category").asText() : null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("seq", seqOf(turnId));
            out.put("kind", kind);
            out.put("level", level);
            out.put("category", category);
            out.put("followUp", question && item.path("followUp").asBoolean(false));
            out.put("topic", question ? item.path("topic").asText("").trim() : "");
            out.put("reason", item.path("reason").asText(""));
            byTurn.put(turnId, out);
        }
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (String turnId : personalTurnIds) if (byTurn.containsKey(turnId)) ordered.add(byTurn.get(turnId));
        return ordered;
    }

    /** {todayLine, changeLine, interestLine, tonightQuestion}. 문제가 고쳐지지 않으면 null(개인화 칸만 비운다). */
    private Map<String, Object> personal(Set<String> usedModels, Input input, StoryView story, List<Map<String, Object>> questions,
            Map<String, Object> summary, Map<String, Map<String, Object>> turnsById, List<String> personalTurnIds) {
        if (personalTurnIds.isEmpty()) return null;
        ObjectNode payload = objectMapper.createObjectNode();
        String childName = input.childName() == null || input.childName().isBlank() ? null : input.childName().trim();
        if (childName == null) payload.putNull("childName");
        else payload.put("childName", childName);
        payload.put("storyTitle", story.title());
        if (input.durationSeconds() == null) payload.putNull("minutes");
        else payload.put("minutes", Math.max(1, Math.round(input.durationSeconds() / 60.0)));
        ObjectNode today = payload.putObject("today");
        today.set("summary", objectMapper.valueToTree(summary));
        ArrayNode asked = today.putArray("questions");
        for (Map<String, Object> q : questions) {
            if (!"QUESTION".equals(q.get("kind"))) continue;
            ObjectNode item = asked.addObject();
            item.put("turnId", "t" + q.get("seq"));
            item.put("text", String.valueOf(turnsById.get("t" + q.get("seq")).get("text")));
            item.put("level", QuestionStats.level(q));
            item.put("category", String.valueOf(q.get("category")));
            item.put("followUp", Boolean.TRUE.equals(q.get("followUp")));
            item.put("topic", String.valueOf(q.get("topic")));
        }
        List<PastSession> history = input.history() == null ? List.of() : input.history();
        payload.set("history", objectMapper.valueToTree(history));

        JsonNode generated = call(usedModels, PERSONAL_SYSTEM, payload, personalSchema(), "report_personal", 1200);
        List<String> problems = personalProblems(generated, childName);
        if (!problems.isEmpty()) {
            payload.set("avoid", objectMapper.valueToTree(problems));
            generated = call(usedModels, PERSONAL_SYSTEM, payload, personalSchema(), "report_personal", 1200);
            problems = personalProblems(generated, childName);
            if (!problems.isEmpty()) {
                log.warn("report-analysis.personal-dropped problems={}", problems);
                return null;
            }
        }
        return personalOut(generated, questions, turnsById, !history.isEmpty());
    }

    /** 코드가 지키는 규칙. 지난 회차가 없으면 비교 문장을 버리고, 오늘 밤 질문은 실제 오늘 질문에 붙은 것만 남긴다. */
    static Map<String, Object> personalOut(JsonNode generated, List<Map<String, Object>> questions,
            Map<String, Map<String, Object>> turnsById, boolean hasHistory) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todayLine", textOrNull(generated.path("todayLine")));
        out.put("changeLine", hasHistory ? textOrNull(generated.path("changeLine")) : null);
        out.put("interestLine", hasHistory ? textOrNull(generated.path("interestLine")) : null);
        Map<String, Object> tonight = null;
        JsonNode t = generated.path("tonightQuestion");
        if (t.isObject() && textOrNull(t.path("text")) != null) {
            int seq = seqOf(t.path("basedOnTurnId").asText());
            boolean realQuestion = questions.stream()
                    .anyMatch(q -> "QUESTION".equals(q.get("kind")) && Integer.valueOf(seq).equals(q.get("seq")));
            if (realQuestion && turnsById.containsKey("t" + seq)) {
                tonight = new LinkedHashMap<>();
                tonight.put("seq", seq);
                tonight.put("childQuestion", String.valueOf(turnsById.get("t" + seq).get("text")));
                tonight.put("text", t.path("text").asText().trim());
            }
        }
        out.put("tonightQuestion", tonight);
        return out;
    }

    /** 아이 이름(로마자일 수 있다)은 빼고 외국어 섞임과 판정 표현을 본다. */
    static List<String> personalProblems(JsonNode generated, String childName) {
        if (childName == null || childName.isBlank()) return textProblems(generated);
        try {
            return textProblems(new ObjectMapper().readTree(generated.toString().replace(childName.trim(), "아이")));
        } catch (Exception malformed) {
            return textProblems(generated);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    private static String lastScene(List<Map<String, Object>> turns) {
        String scene = null;
        for (Map<String, Object> turn : turns) if (turn.get("sceneId") != null) scene = String.valueOf(turn.get("sceneId"));
        return scene;
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
                    "helpStep", "event", "familyId", "viaSuggestion", "suggestionLabel", "proposedFamilyId", "attemptTurns")) {
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

    private JsonNode call(
            Set<String> usedModels, String system, ObjectNode payload, ObjectNode schema, String name, int maxTokens) {
        OpenRouterClient.StructuredCompletion completion = openRouterClient.structuredCompletion(
                modelId, system, List.of(), payload.toString(), schema, name, maxTokens, 0.2,
                ProviderErrorCode.OPENROUTER_RESPONSE_FAILED, "리포트 분석을 만들지 못했어요.",
                RequestDeadline.startingNow(90_000));
        if (completion.model() != null && !completion.model().isBlank()) usedModels.add(completion.model());
        return completion.content();
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

    private ObjectNode classifySchema() {
        ObjectNode item = obj("turnId", "kind", "level", "category", "followUp", "topic", "reason");
        ObjectNode p = (ObjectNode) item.get("properties");
        p.set("turnId", str());
        p.set("kind", enumOf("QUESTION", "PROPOSAL", "OTHER"));
        ObjectNode level = objectMapper.createObjectNode();
        level.put("type", "integer");
        level.putArray("enum").add(0).add(1).add(2).add(3).add(4);
        p.set("level", level);
        p.set("category", enumOf("NARRATIVE", "FACT_CAUSE", "MIND_RELATION", "NONE"));
        p.set("followUp", bool());
        p.set("topic", str());
        p.set("reason", str());
        ObjectNode root = obj("items");
        ((ObjectNode) root.get("properties")).set("items", arrayOf(item));
        return root;
    }

    private ObjectNode personalSchema() {
        ObjectNode tonight = obj("basedOnTurnId", "text");
        ((ObjectNode) tonight.get("properties")).set("basedOnTurnId", str());
        ((ObjectNode) tonight.get("properties")).set("text", str());
        ObjectNode nullableTonight = objectMapper.createObjectNode();
        nullableTonight.putArray("anyOf").add(tonight).add(objectMapper.createObjectNode().put("type", "null"));
        ObjectNode root = obj("todayLine", "changeLine", "interestLine", "tonightQuestion");
        ObjectNode p = (ObjectNode) root.get("properties");
        p.set("todayLine", str());
        p.set("changeLine", nullableStr());
        p.set("interestLine", nullableStr());
        p.set("tonightQuestion", nullableTonight);
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
