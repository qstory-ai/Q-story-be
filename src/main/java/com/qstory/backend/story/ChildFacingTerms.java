package com.qstory.backend.story;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 아이에게 보이는·들리는 글에서 쓰지 않는 호칭을 장면에 맞는 말로 바꾼다.
 *
 * <p>헨젤과 그레텔의 과자집 주인은 정체가 드러나기 전에는 "할머니", 드러난 뒤에는 "마녀"다. "노파"는
 * 아이 언어 규칙(fe content/language-rules.yaml)에서 금지한 말인데, 캐스트 이름·라우팅 예시·금지 지식
 * 문구에 남아 있어 AI가 그대로 따라 쓴 적이 있다. 프롬프트에 규칙을 넣고(promptRule), AI에게 보내는 글과
 * AI가 만든 글을 모두 이 클래스로 한 번 더 거른다(apply) - 프롬프트를 어겨도 아이에게는 나가지 않게.
 *
 * <p>"노파"·"할머니"·"마녀" 모두 모음으로 끝나 뒤에 붙은 조사(가/는/를/에게/의)를 그대로 둬도 맞는다.
 */
public final class ChildFacingTerms {

    /**
     * @param word         쓰지 않는 말
     * @param beforeReveal 정체가 드러나기 전 호칭
     * @param afterReveal  드러난 뒤 호칭
     * @param revealScene  정체가 드러나는 장면 번호(sceneId 끝의 숫자, HG-F06이면 6)
     * @param revealMarker 그 장면 안에서 이미 드러났는지 보는 말 - 방금 들은 대사에 이 말이 있으면 드러난 뒤다
     */
    record Rule(String word, String beforeReveal, String afterReveal, int revealScene, String revealMarker) {}

    private static final Map<String, Rule> RULES = Map.of(
            "HG", new Rule("노파", "할머니", "마녀", 6, "마녀"));
    private static final Pattern SCENE_NUMBER = Pattern.compile("(\\d+)$");
    private static final ChildFacingTerms NONE = new ChildFacingTerms(null, false);

    private final Rule rule;
    private final boolean revealed;

    private ChildFacingTerms(Rule rule, boolean revealed) {
        this.rule = rule;
        this.revealed = revealed;
    }

    public static ChildFacingTerms none() {
        return NONE;
    }

    /** 장면만 알 때 - 정체가 드러나는 장면 안이면 아직 드러나기 전으로 본다. */
    public static ChildFacingTerms forScene(String storyId, String sceneId) {
        return forScene(storyId, sceneId, List.of());
    }

    /**
     * @param linesJustHeard 지금 장면에서 방금 들은 대사 - 정체가 드러나는 장면 안에서 이미 드러났는지 가른다.
     */
    public static ChildFacingTerms forScene(String storyId, String sceneId, List<String> linesJustHeard) {
        Rule rule = storyId == null ? null : RULES.get(storyId);
        if (rule == null) {
            return NONE;
        }
        int scene = sceneNumber(sceneId);
        boolean revealed = scene > rule.revealScene()
                || (scene == rule.revealScene() && linesJustHeard != null
                        && linesJustHeard.stream().anyMatch(line -> line != null && line.contains(rule.revealMarker())));
        return new ChildFacingTerms(rule, revealed);
    }

    /** 이야기 id 없이 장면 id(HG-F05처럼 "이야기-장면")만 있을 때 - 리포트 분석이 이 경우다. */
    public static ChildFacingTerms forSceneId(String sceneId) {
        if (sceneId == null || sceneId.indexOf('-') <= 0) {
            return NONE;
        }
        return forScene(sceneId.substring(0, sceneId.indexOf('-')), sceneId);
    }

    private static int sceneNumber(String sceneId) {
        if (sceneId == null) {
            return -1;
        }
        Matcher matcher = SCENE_NUMBER.matcher(sceneId.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** 지금 장면에서 그 인물을 부를 말. 규칙이 없는 이야기면 null. */
    public String currentTerm() {
        return rule == null ? null : revealed ? rule.afterReveal() : rule.beforeReveal();
    }

    public String apply(String text) {
        if (rule == null || text == null || !text.contains(rule.word())) {
            return text;
        }
        return text.replace(rule.word(), currentTerm());
    }

    /**
     * 시스템 프롬프트에 넣을 호칭 규칙 한 줄. 규칙이 없는 이야기면 null. 드러나기 전에는 드러난 뒤의 호칭을
     * 알려 주지 않는다 - 규칙 문장이 정체를 미리 흘리면 안 된다.
     */
    public String promptRule() {
        if (rule == null) {
            return null;
        }
        String naming = revealed
                ? "과자집 주인은 이제 정체가 드러났으니 '" + rule.afterReveal() + "'라고 부른다."
                : "과자집에서 나온 사람은 '" + rule.beforeReveal() + "'라고 부른다.";
        return naming + " '" + rule.word() + "'라는 말은 쓰지 않는다.";
    }
}
