package com.qstory.backend.story;

import com.qstory.backend.choicecopy.ChoiceCopyRepository;
import com.qstory.backend.common.enums.CastRole;
import com.qstory.backend.common.enums.StoryAvailability;
import com.qstory.backend.persistence.entity.StoryActionFamilyEntity;
import com.qstory.backend.persistence.entity.StoryAnchorEntity;
import com.qstory.backend.persistence.entity.StoryCastEntity;
import com.qstory.backend.persistence.entity.StoryEntity;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the one currently-authored story (Hansel & Gretel) on first boot against a fresh
 * database. This is the single source of truth for that content going forward - once seeded,
 * edit it via the DB (or a future admin tool), not by re-running this class. No-ops if the
 * "HG" row already exists.
 */
@Component
@Order(0)
public class StoryContentSeeder implements ApplicationRunner {

    private final StoryContentRepository contentRepository;
    private final ChoiceCopyRepository copyRepository;

    public StoryContentSeeder(StoryContentRepository contentRepository, ChoiceCopyRepository copyRepository) {
        this.contentRepository = contentRepository;
        this.copyRepository = copyRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (contentRepository.existsStory("HG")) {
            return;
        }
        StoryEntity story = contentRepository.saveStory(StoryEntity.builder()
                .id("HG").slug("hansel-gretel").title("헨젤과 그레텔")
                .contentVersion("master-spec-v2.4-branch-integrity")
                .availability(StoryAvailability.BETA)
                .routePromptVersion("QSTORY_ROUTE_PROMPT_V6_COVERAGE")
                .routePolicyVersion("qstory-route-policy-v3-coverage")
                .responseTextNormalizationVersion("qstory-ko-response-normalizer-v2")
                .castVersion("hg-gemini-tts-cast-v2")
                .build());

        seedAnchorA(story);
        seedAnchorB(story);
        seedAnchorC(story);
        seedCast(story);
    }

    private void seedAnchorA(StoryEntity story) {
        StoryAnchorEntity anchor = contentRepository.saveAnchor(StoryAnchorEntity.builder()
                .id("HG-Q-A").story(story).slot("A").sceneId("HG-F04")
                .summary("헨젤과 그레텔이 숲에서 길을 잃었고, 낮은 나뭇가지의 하얀 새가 두 아이를 바라보며 길을 알려줄 듯 움직인다.")
                .primarySpeakerId("HG-SPK-GRETEL")
                .allowedSpeakerIds(List.of("HG-SPK-GRETEL"))
                .sttKeywords(List.of("헨젤", "그레텔", "하얀 새", "숲", "길", "냄새"))
                .defaultFallbackFamilyId("A_OBSERVE_BIRD")
                .defaultRejoinAt("HG-F04-CANDY-HOUSE-REVEAL")
                .forbiddenKnowledge(List.of("과자집 노파의 정체", "마녀의 계획", "최종 탈출 방법과 결말"))
                .concernChoiceFamilyIds(List.of("A_OBSERVE_BIRD", "A_SPEAK_TO_BIRD", "A_CHECK_SURROUNDINGS"))
                .concernChoiceResponseText("그렇게 걱정될 수 있어. 바로 따라가기 전에 안전하게 확인할 방법을 함께 골라 보자.")
                .build());

        family(anchor, 0, "A_OBSERVE_BIRD", "안전한 거리에서 새의 행동을 관찰한다.",
                "좋아, 우리 가까이 가지 말고 하얀 새를 조용히 지켜보자.",
                "남매는 거리를 둔 채 새의 시선과 단 냄새를 확인하고 손을 잡은 채 새를 따라갔어요.",
                "BRIDGE-A_OBSERVE_BIRD", "HG-FB-ART-A-OBSERVE-BIRD-V1", List.of(),
                "새의 눈길 따라가기", "하얀 새가 바라보는 방향과 몸짓을 조용히 관찰한다.",
                "새가 보는 곳 살피기", "새가 코끝을 든 방향에서 단 냄새가 나는지 확인한다.",
                "조용히 새 지켜보기", "안전한 거리를 두고 새가 남매를 기다리는지 살펴본다.");
        family(anchor, 1, "A_SPEAK_TO_BIRD", "하얀 새에게 길을 아는지 말로 묻는다.",
                "좋아, 내가 하얀 새에게 길을 아는지 다정하게 물어볼게.",
                "그레텔이 하얀 새에게 말을 걸자 새가 앞선 가지에서 기다렸고 남매는 천천히 따라갔어요.",
                "BRIDGE-A_SPEAK_TO_BIRD", "HG-FB-ART-A-SPEAK-TO-BIRD-V1", List.of(),
                "새에게 길 물어보기", "하얀 새에게 숲을 나가는 길을 아는지 다정하게 묻는다.",
                "하얀 새와 인사하기", "새가 놀라지 않게 인사한 뒤 알려주고 싶은 것이 있는지 묻는다.",
                "새의 대답 기다리기", "낮은 목소리로 말을 건 뒤 새가 몸짓으로 답하는지 살핀다.");
        family(anchor, 2, "A_CHECK_SURROUNDINGS", "새 주변의 발자국·냄새·나뭇가지 방향을 확인한다.",
                "좋아, 새를 따라가기 전에 주변에 남은 단서부터 찾아보자.",
                "남매는 빵 부스러기·발자국·깃털과 단 냄새를 함께 확인한 뒤 걸어갈 방향을 정했어요.",
                "BRIDGE-A_CHECK_SURROUNDINGS", "HG-FB-ART-A-CHECK-SURROUNDINGS-V1", List.of(),
                "숲의 흔적 찾아보기", "발자국과 나뭇가지, 냄새를 차례로 살펴 길의 단서를 찾는다.",
                "바닥 단서 살펴보기", "나무 아래의 빵 부스러기와 깃털이 향한 방향을 확인한다.",
                "달콤한 냄새 좇기", "제자리에서 바람이 불어오는 방향과 희미한 단 냄새를 확인한다.");
        family(anchor, 3, "A_TRY_OTHER_PATH", "새를 바로 따라가지 않고 반대쪽 길을 짧게 확인한다.",
                "좋아, 새를 바로 따라가지 말고 다른 안전한 길도 살펴보자.",
                "반대쪽 길이 가시덤불과 쓰러진 나무로 막힌 것을 확인한 남매는 새가 알려 준 열린 길로 돌아왔어요.",
                "BRIDGE-A_TRY_OTHER_PATH", "HG-FB-ART-A-TRY-OTHER-PATH-V1", List.of(),
                "반대쪽 길 확인하기", "새를 바로 따라가지 않고 반대쪽 길이 안전한지 짧게 살펴본다.",
                "다른 길 먼저 보기", "반대편 길에 쓰러진 나무나 가시덤불이 있는지 확인한다.",
                "열린 길 다시 고르기", "다른 길이 막혔는지 본 뒤 새가 알려 준 열린 길로 돌아온다.");
    }

    private void seedAnchorB(StoryEntity story) {
        StoryAnchorEntity anchor = contentRepository.saveAnchor(StoryAnchorEntity.builder()
                .id("HG-Q-B").story(story).slot("B").sceneId("HG-F05")
                .summary("아이들이 과자집을 발견했고 노파가 문을 열었다. 문 주변에는 열쇠와 설탕 무늬 같은 수상한 단서가 있다.")
                .primarySpeakerId("HG-SPK-GRETEL")
                .allowedSpeakerIds(List.of("HG-SPK-GRETEL"))
                .sttKeywords(List.of("헨젤", "그레텔", "할머니", "노파", "과자집", "열쇠", "자물쇠", "창문", "출구", "신호"))
                .defaultFallbackFamilyId("B_CHECK_KEYS")
                .defaultRejoinAt("HG-F05-ENTER-HOUSE")
                .forbiddenKnowledge(List.of("노파가 마녀라는 사실", "헨젤이 갇히는 미래 사건", "최종 탈출 방법과 결말"))
                .concernChoiceFamilyIds(List.of("B_ASK_OLD_WOMAN", "B_CHECK_HOUSE", "B_MAKE_SIBLING_SIGNAL"))
                .concernChoiceResponseText("나도 조금 수상하게 느껴져. 바로 들어가기 전에 안전하게 확인할 방법을 함께 골라 보자.")
                .build());

        family(anchor, 0, "B_ASK_OLD_WOMAN", "노파에게 집과 열쇠에 관해 짧게 질문한다.",
                "좋아, 내가 할머니에게 이 집과 열쇠에 관해 조심스럽게 물어볼게.",
                "노파는 질문에 얼버무리면서도 열쇠고리를 꼭 잡았고 남매는 그 수상한 단서를 기억했어요.",
                "BRIDGE-B_ASK_OLD_WOMAN", "HG-FB-ART-B-ASK-OLD-WOMAN-V1", List.of(),
                "할머니께 집 물어보기", "노파에게 숲 한가운데 이 집이 있는 이유를 조심스럽게 묻는다.",
                "숲속 집 이유 묻기", "왜 숲 한가운데서 혼자 사는지 묻고 대답을 주의 깊게 듣는다.",
                "노파의 대답 살피기", "길 잃은 손님을 기다렸다는 말이 구체적인지 확인한다.");
        family(anchor, 1, "B_CHECK_KEYS", "문 주변의 열쇠와 자물쇠를 살핀다.",
                "좋아, 할머니 손에 있는 열쇠고리부터 자세히 살펴보자.",
                "그레텔은 검은 열쇠와 설탕 무늬가 함께 반응하는 것을 발견해 헨젤에게 알려줬어요.",
                "BRIDGE-B_CHECK_KEYS", "HG-FB-ART-B-CHECK-KEYS-V1", List.of(),
                "검은 열쇠 살펴보기", "노파의 열쇠고리에서 가장 큰 검은 열쇠를 자세히 본다.",
                "열쇠와 무늬 비교하기", "검은 열쇠가 흔들릴 때 현관 안쪽 설탕 무늬도 바뀌는지 본다.",
                "같이 반응하는지 보기", "열쇠의 움직임과 설탕 소용돌이의 검은 빛이 연결됐는지 확인한다.");
        family(anchor, 2, "B_CHECK_HOUSE", "문턱 밖에서 집의 창문과 출구 단서를 확인한다.",
                "좋아, 안으로 들어가기 전에 창문과 출구를 먼저 확인하자.",
                "남매는 문턱 밖에서 창문 가장자리의 철선을 발견하고 출구 단서를 기억했어요.",
                "BRIDGE-B_CHECK_HOUSE", "HG-FB-ART-B-CHECK-HOUSE-V1", List.of(),
                "창문과 출구 확인하기", "문턱 밖에서 창문과 돌아나올 길이 있는지 살펴본다.",
                "창문 가장자리 보기", "설탕 창문 가장자리에 숨은 가느다란 철선을 확인한다.",
                "문틀 밖에서 살피기", "집 안으로 들어가지 않고 창문과 문틀의 수상한 단서를 찾는다.");
        family(anchor, 3, "B_STEP_BACK_MARK_EXIT", "한 걸음 물러나 안전한 출구 위치를 표시한다.",
                "좋아, 한 걸음 물러나서 돌아갈 길을 잊지 않게 표시하자.",
                "남매는 돌아갈 표시를 남겼지만 길이 과자집 주위를 도는 것을 알았고 마녀의 함정 속에서도 그 표시를 기억했어요.",
                "BRIDGE-B_STEP_BACK_MARK_EXIT", "HG-FB-ART-B-STEP-BACK-MARK-EXIT-V1", List.of(),
                "돌아갈 길 표시하기", "한 걸음 물러나 나무에 안전한 귀환 표시를 남긴다.",
                "과자집과 거리 두기", "바로 들어가지 않고 표시한 길이 이어지는지 확인한다.",
                "숲길 방향 기억하기", "과자집을 등졌을 때 보이는 나무와 길 모양을 기억해 둔다.");
        family(anchor, 4, "B_MAKE_SIBLING_SIGNAL", "헨젤과 그레텔이 서로 알아볼 안전 신호를 정한다.",
                "좋아, 무슨 일이 생기면 바로 알 수 있게 우리끼리 신호를 정하자.",
                "남매는 위험할 때 무엇이든 두 번 두드리는 비밀 신호를 정하고 함께 기억했어요.",
                "BRIDGE-B_MAKE_SIBLING_SIGNAL", "HG-FB-ART-B-MAKE-SIBLING-SIGNAL-V1", List.of(),
                "남매 비밀 신호 정하기", "이상한 일이 생기면 무엇이든 두 번 두드리는 신호를 정한다.",
                "두 번 두드리기 약속", "서로 떨어져도 알아들을 수 있게 두 번 두드리는 신호를 만든다.",
                "탁, 탁 약속하기", "문·그릇·나무 어디에서든 탁, 탁 두 번으로 서로의 신호를 보낸다.");
    }

    private void seedAnchorC(StoryEntity story) {
        StoryAnchorEntity anchor = contentRepository.saveAnchor(StoryAnchorEntity.builder()
                .id("HG-Q-C").story(story).slot("C").sceneId("HG-F07")
                .summary("마녀가 자신은 화덕에서 멀리 서서 열쇠고리를 쥔 채 그레텔에게만 안을 보라고 한다. 명령의 의도는 아직 설명되지 않았다.")
                .primarySpeakerId("HG-SPK-GRETEL")
                .allowedSpeakerIds(List.of("HG-SPK-GRETEL"))
                .sttKeywords(List.of("헨젤", "그레텔", "마녀", "오븐", "화덕", "열쇠고리", "쇠창살", "시범", "신호", "자물쇠"))
                .defaultFallbackFamilyId("C_ASK_DEMONSTRATION")
                .defaultRejoinAt("HG-F07-DEMONSTRATION")
                .forbiddenKnowledge(List.of("구체적인 폭력 방법", "화덕을 이용해 상대를 해치는 묘사", "최종 결말의 선공개"))
                .concernChoiceFamilyIds(List.of("C_ASK_DEMONSTRATION", "C_USE_SIGNAL", "C_CHECK_LOCK_FROM_DISTANCE"))
                .concernChoiceResponseText("그 걱정이 맞아. 가까이 가지 말고 안전하게 움직일 방법을 함께 골라 보자.")
                .build());

        family(anchor, 0, "C_ASK_DEMONSTRATION", "그레텔이 모르는 척하며 먼저 시범을 보여 달라고 한다.",
                "좋아, 내가 모르는 척하면서 마녀에게 먼저 보여 달라고 해볼게.",
                "그레텔은 화덕에서 멀리 서서 먼저 보여 달라고 했고 마녀가 열쇠를 든 채 앞으로 나서게 했어요.",
                "BRIDGE-C_ASK_DEMONSTRATION", "HG-FB-ART-C-ASK-DEMONSTRATION-V1", List.of(),
                "마녀에게 먼저 부탁하기", "그레텔이 모르는 척하며 화덕을 먼저 확인해 달라고 한다.",
                "시범을 보여 달라 하기", "마녀가 가까이 가서 어떻게 하는지 직접 보여 달라고 부탁한다.",
                "방법을 다시 묻기", "위험하게 다가가지 않고 정확한 방법을 천천히 보여 달라고 한다.");
        family(anchor, 1, "C_DISTRACT_AND_TAKE_KEYS", "마녀의 주의를 안전하게 돌리고 열쇠를 확보한다.",
                "좋아, 마녀의 시선을 다른 곳으로 돌리고 열쇠를 챙길 기회를 찾아보자.",
                "그레텔은 냄비로 마녀의 시선을 돌려 열쇠를 얻고 헨젤을 풀어 함께 복도로 빠져나갔어요.",
                "BRIDGE-C_DISTRACT_AND_TAKE_KEYS", "HG-FB-ART-C-DISTRACT-AND-TAKE-KEYS-V1", List.of(),
                "시선 돌리고 열쇠 보기", "마녀가 다른 곳을 보는 사이 작업대의 열쇠를 확인한다.",
                "떨어질 냄비 알려주기", "작업대 끝의 빈 냄비가 떨어질 것 같다고 말해 마녀의 시선을 돌린다.",
                "열쇠 놓을 순간 기다리기", "마녀가 시범을 위해 열쇠고리를 내려놓는 순간을 기다린다.");
        family(anchor, 2, "C_USE_SIGNAL", "남매가 문 앞에서 나눈 두 번 두드리기 약속을 떠올려 움직일 순간을 맞춘다.",
                "좋아, 문 앞에서 헨젤과 나눈 약속대로 두 번 두드리는 신호를 보낼게.",
                "그레텔은 과자집 문 앞에서 나눈 약속을 떠올리고, 헨젤과 빈손으로 탁, 탁 신호를 주고받아 마녀의 시선을 돌린 뒤 함께 탈출했어요.",
                "BRIDGE-C_USE_SIGNAL", "HG-FB-ART-C-USE-SIGNAL-V2", List.of("B_MAKE_SIBLING_SIGNAL"),
                "헨젤에게 신호 보내기", "작업대를 두 번 두드려 헨젤에게 준비하라는 신호를 보낸다.",
                "남매가 함께 움직이기", "두 번 두드리는 신호로 서로의 움직일 때를 맞춘다.",
                "쇠창살 답신 기다리기", "그레텔이 신호를 보낸 뒤 헨젤의 두 번 두드리는 답을 기다린다.");
        family(anchor, 3, "C_CHECK_LOCK_FROM_DISTANCE", "화덕에 다가가지 않고 열쇠와 잠금 상태를 확인한다.",
                "좋아, 위험하게 다가가지 말고 멀리서 자물쇠 상태를 확인하자.",
                "그레텔은 긴 빵 주걱으로 잠금 무늬와 철문이 연결된 것을 알아내고 마녀에게 먼저 시범을 요청했어요.",
                "BRIDGE-C_CHECK_LOCK_FROM_DISTANCE", "HG-FB-ART-C-CHECK-LOCK-FROM-DISTANCE-V1", List.of(),
                "긴 주걱으로 확인하기", "화덕에 다가가지 않고 긴 빵 주걱으로 잠금 무늬를 살핀다.",
                "멀리서 자물쇠 살피기", "안전한 거리에서 열쇠와 잠금장치가 연결됐는지 확인한다.",
                "떨리는 철문 관찰하기", "주걱을 가까이 댔을 때 쇠창살과 철문이 움직이는지 본다.");
        family(anchor, 4, "C_BLOCK_PURSUIT_SAFELY", "문과 주변 물건을 이용해 안전하게 추격을 늦춘다.",
                "좋아, 아무도 다치지 않게 문과 주변 물건으로 마녀를 늦춰보자.",
                "남매는 열쇠로 헨젤을 풀고 큰 철문을 내려 마녀의 추격을 늦춘 뒤 함께 달아났어요.",
                "BRIDGE-C_BLOCK_PURSUIT_SAFELY", "HG-FB-ART-C-BLOCK-PURSUIT-SAFELY-V1", List.of(),
                "문으로 길 막아두기", "아무도 다치지 않게 문을 닫아 마녀의 추격을 늦춘다.",
                "사탕 철문 내리기", "검은 열쇠를 반대로 돌려 마녀 앞의 사탕 철문을 내린다.",
                "안전하게 추격 늦추기", "헨젤을 먼저 풀어 준 뒤 문과 자물쇠로 거리를 벌린다.");
    }

    private void family(
            StoryAnchorEntity anchor, int order, String id, String meaning, String acknowledgementText,
            String reportSummary, String bridgeAudioId, String branchAssetId, List<String> requiresPriorFamilyIds,
            String label1, String meaning1, String label2, String meaning2, String label3, String meaning3) {
        StoryActionFamilyEntity family = contentRepository.saveActionFamily(StoryActionFamilyEntity.builder()
                .id(id).anchor(anchor).meaning(meaning).acknowledgementText(acknowledgementText)
                .reportSummary(reportSummary).bridgeAudioId(bridgeAudioId).branchAssetId(branchAssetId)
                .requiresPriorFamilyIds(requiresPriorFamilyIds).displayOrder(order)
                .build());
        copyRepository.saveVariant(family, 0, label1, meaning1);
        copyRepository.saveVariant(family, 1, label2, meaning2);
        copyRepository.saveVariant(family, 2, label3, meaning3);
    }

    private void seedCast(StoryEntity story) {
        contentRepository.saveCast(StoryCastEntity.builder()
                .story(story).castTag("NARRATOR").speakerId("HG-SPK-NARRATOR").role(CastRole.NARRATOR)
                .displayName("이야기꾼 Q").voice("Sulafat")
                .profile("A warm, steady adult woman who narrates a Korean picture book for children ages six to nine.")
                .direction("Speak clearly and calmly. Keep suspense gentle and never exaggerate fear.")
                .build());
        contentRepository.saveCast(StoryCastEntity.builder()
                .story(story).castTag("FATHER").speakerId("HG-SPK-FATHER").role(CastRole.CHARACTER)
                .displayName("아버지").voice("Charon")
                .profile("A tired middle-aged father whose worry, avoidance, regret, and responsibility remain distinct.")
                .direction("Use a low, restrained delivery. In the ending, sound sincere and accountable rather than sentimental.")
                .build());
        contentRepository.saveCast(StoryCastEntity.builder()
                .story(story).castTag("STEPMOTHER").speakerId("HG-SPK-STEPMOTHER").role(CastRole.CHARACTER)
                .displayName("새어머니").voice("Kore")
                .profile("A sharp, stubborn adult woman. She is intimidating but is not a monster or the witch.")
                .direction("Speak briefly, firmly, and coldly. Do not use a supernatural or caricatured villain voice.")
                .build());
        contentRepository.saveCast(StoryCastEntity.builder()
                .story(story).castTag("HANSEL").speakerId("HG-SPK-HANSEL").role(CastRole.CHARACTER)
                .displayName("헨젤").voice("Puck")
                .profile("A thoughtful young boy who is clever but still vulnerable and imperfect.")
                .direction("Sound youthful and careful. Become slightly more confident only when explaining a plan.")
                .build());
        contentRepository.saveCast(StoryCastEntity.builder()
                .story(story).castTag("GRETEL").speakerId("HG-SPK-GRETEL").role(CastRole.CHARACTER)
                .displayName("그레텔").voice("Leda")
                .profile("A perceptive young girl who speaks directly and kindly to the child listening.")
                .direction("Sound youthful, curious, and warm in questions; calm and decisive during the escape.")
                .build());
        contentRepository.saveCast(StoryCastEntity.builder()
                .story(story).castTag("OLD_WOMAN").speakerId("HG-SPK-OLD_WOMAN").role(CastRole.CHARACTER)
                .displayName("노파").voice("Gacrux").samePersonKey("OLD_WOMAN_WITCH")
                .profile("An elderly Korean woman with a mature, slightly breathy voice. She sounds grandmotherly, gentle, and faintly mysterious.")
                .direction("Use an older woman's slower cadence, soft breath, and clear articulation. Keep the invitation warm but subtly uncanny.")
                .build());
        contentRepository.saveCast(StoryCastEntity.builder()
                .story(story).castTag("WITCH").speakerId("HG-SPK-WITCH").role(CastRole.CHARACTER)
                .displayName("마녀").voice("Gacrux").samePersonKey("OLD_WOMAN_WITCH")
                .profile("The exact same elderly Korean woman as OLD_WOMAN, with the same age and core vocal identity after her disguise is revealed.")
                .direction("Preserve the same mature female voice. Remove all warmth and speak coldly, firmly, and impatiently without becoming a male voice.")
                .build());
    }
}
