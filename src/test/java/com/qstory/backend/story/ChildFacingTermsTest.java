package com.qstory.backend.story;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** "노파"는 아이에게 보이지 않는다 - 정체가 드러나기 전에는 할머니, 드러난 뒤에는 마녀. */
class ChildFacingTermsTest {

    @Test
    void beforeTheRevealSceneTheOldWomanIsGrandma() {
        ChildFacingTerms terms = ChildFacingTerms.forScene("HG", "HG-F05");
        assertThat(terms.apply("노파가 문을 열었어. 노파에게 물어볼까?")).isEqualTo("할머니가 문을 열었어. 할머니에게 물어볼까?");
        assertThat(terms.currentTerm()).isEqualTo("할머니");
    }

    @Test
    void afterTheRevealSceneSheIsTheWitch() {
        assertThat(ChildFacingTerms.forScene("HG", "HG-F07").apply("노파가 또 돌아봐")).isEqualTo("마녀가 또 돌아봐");
        assertThat(ChildFacingTerms.forScene("HG", "HG-F10").currentTerm()).isEqualTo("마녀");
    }

    @Test
    void insideTheRevealSceneItDependsOnWhatWasJustHeard() {
        assertThat(ChildFacingTerms.forScene("HG", "HG-F06", List.of("내레이터: 할머니가 부엌 문을 잠갔어요.")).currentTerm())
                .isEqualTo("할머니");
        assertThat(ChildFacingTerms.forScene("HG", "HG-F06", List.of("마녀: 집에 갈 생각은 하지 마.")).currentTerm())
                .isEqualTo("마녀");
        assertThat(ChildFacingTerms.forScene("HG", "HG-F06").currentTerm()).isEqualTo("할머니");
    }

    @Test
    void ruleSentenceDoesNotLeakTheRevealEarly() {
        assertThat(ChildFacingTerms.forScene("HG", "HG-F04").promptRule())
                .contains("'할머니'라고 부른다").contains("'노파'라는 말은 쓰지 않는다").doesNotContain("마녀");
        assertThat(ChildFacingTerms.forScene("HG", "HG-F08").promptRule()).contains("'마녀'라고 부른다");
    }

    @Test
    void otherStoriesAndUnknownScenesAreSafe() {
        assertThat(ChildFacingTerms.forScene("OTHER", "X-F01").apply("노파")).isEqualTo("노파");
        assertThat(ChildFacingTerms.forScene("OTHER", "X-F01").promptRule()).isNull();
        assertThat(ChildFacingTerms.forScene("HG", null).currentTerm()).isEqualTo("할머니");
        assertThat(ChildFacingTerms.none().apply(null)).isNull();
    }
}
