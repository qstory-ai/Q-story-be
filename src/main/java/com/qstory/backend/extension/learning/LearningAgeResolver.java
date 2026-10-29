package com.qstory.backend.extension.learning;

import com.qstory.backend.common.util.ChildAge;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * StoryPal 나이대를 대화 상대 아이의 프로필에서 채운다. 화면은 선생님 반 학생의 출생연도를 모를 때가 있어 서버에서 찾는다.
 *
 * <p>요청이 이미 나이대를 알거나(UNKNOWN이 아니면) StoryPal이 꺼져 있으면 손대지 않는다. 첫 답에 채운 나이대가
 * 학습 상태로 돌아가 다음 요청부터 실려 오므로, 조회는 대화마다 한 번이다. 요청한 사람의 아이·학생일 때만 쓴다
 * (보호자 = 자기 아이, 선생님 = 자기 학생). 찾지 못하면 UNKNOWN 그대로 - 나이를 추측하지 않는다.
 */
@Component
public class LearningAgeResolver {

    private static final Logger log = LoggerFactory.getLogger(LearningAgeResolver.class);
    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");

    private final ChildRepository children;
    private final TutorStudentRepository students;

    public LearningAgeResolver(ChildRepository children, TutorStudentRepository students) {
        this.children = children;
        this.students = students;
    }

    public LearningState apply(LearningState state, ConversationAttribution who) {
        if (!ExtensionFlags.storyPal() || state == null || !"UNKNOWN".equals(state.ageBand())
                || who == null || who.userId() == null) {
            return state;
        }
        String band;
        try {
            band = band(age(who));
        } catch (RuntimeException failure) {
            // 나이대는 질문 난도 참고값일 뿐이라 조회가 실패해도 대화는 그대로 간다.
            log.warn("storypal.age-lookup-failed reason={}", failure.getClass().getSimpleName());
            return state;
        }
        if ("UNKNOWN".equals(band)) {
            return state;
        }
        // 첫 턴이면 나이대에 맞는 시작 난도로, 대화 중간이면 지금까지의 상태는 두고 나이대만 바꾼다.
        return state.turnIndex() == 0 ? LearningState.initial(band) : new LearningState(
                band, state.difficulty(), state.hintLevel(), state.successStreak(), state.turnIndex(),
                state.lastQuestionType(), state.peerPhase());
    }

    private Integer age(ConversationAttribution who) {
        if (who.childId() != null) {
            return children.findByIdAndParent_Id(who.childId(), who.userId())
                    .map(child -> ageOf(child.getBirthYear(), child.getAgeBand())).orElse(null);
        }
        if (who.tutorStudentId() != null) {
            return students.findByIdAndTutor_IdAndDeletedAtIsNull(who.tutorStudentId(), who.userId())
                    .map(student -> ageOf(student.getBirthYear(), student.getAgeBand())).orElse(null);
        }
        return null;
    }

    /** 출생연도가 있으면 연 나이, 예전 프로필은 저장된 라벨("6-7", "7세")의 첫 숫자. */
    static Integer ageOf(Integer birthYear, String storedBand) {
        if (birthYear != null) {
            return ChildAge.ageInYears(birthYear);
        }
        Matcher number = FIRST_NUMBER.matcher(Optional.ofNullable(storedBand).orElse(""));
        return number.find() ? Integer.valueOf(number.group()) : null;
    }

    /** 키트의 나이대는 5-6(쉬운 말·한 가지 생각)과 7-9(이유·비교 한 단계 더) 둘뿐 - 6세 이하는 앞, 7세 이상은 뒤. */
    static String band(Integer age) {
        if (age == null) {
            return "UNKNOWN";
        }
        return age <= 6 ? "5-6" : "7-9";
    }
}
