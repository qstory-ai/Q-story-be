package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * 076 조회 규칙이 쿼리에 들어 있는지 - 이 저장소에는 DB 통합 테스트가 없어(SQL은 부팅·실제 요청으로 확인) 규칙이 빠지면
 * 바로 알 수 있게 쿼리 문자열을 본다.
 */
class ClassLifecycleQueryContractTest {

    @Test
    void parentClassSessionVisibilityFollowsClassHistory() throws Exception {
        String rule = StoryCompletionRepository.CLASS_MEMBER_SEES_SESSION;
        assertTrue(rule.contains("s.graduated_at is null"), "졸업한 아이는 지금 반 기록을 날짜 제한 없이 보지 않는다");
        assertTrue(rule.contains("tutor_student_class_history"), rule);
        assertTrue(rule.contains("c.completed_at <= h.ended_at"), "떠난 반 기록은 떠난 날까지만");
        for (String name : new String[] {"findVisibleClassSessionNames", "isVisibleToClassParent", "findLinkedChildren"}) {
            assertTrue(query(StoryCompletionRepository.class, name).contains("tutor_student_class_history"), name);
        }
    }

    @Test
    void activeRosterSeatAndCountQueriesExcludeGraduatedStudents() throws Exception {
        for (String name : new String[] {
                "findByClassGroup_IdAndDeletedAtIsNullOrderByCreatedAtAsc", "countByClassGroupInOrganization",
                "findByClassGroup_IdAndTutorIsNullAndDeletedAtIsNull", "existsByClassGroup_IdAndChild_IdAndDeletedAtIsNull",
                "countByClassGroup_Organization_IdAndDeletedAtIsNull", "countLinkedParentsByOrganization",
                "findClassSeatsOfParent", "countByClassGroup_Organization_IdAndDeletedAtIsNullAndLinkedParentUserIsNotNull",
                "countEarlierInOrganization", "findByClassGroup_IdAndLinkedParentUserIsNullAndDeletedAtIsNull"}) {
            assertTrue(query(TutorStudentRepository.class, name).contains("graduatedAt is null"), name);
        }
    }

    private static String query(Class<?> repository, String name) {
        for (Method method : repository.getMethods()) {
            if (method.getName().equals(name) && method.getAnnotation(Query.class) != null) {
                return method.getAnnotation(Query.class).value();
            }
        }
        throw new AssertionError("no @Query " + name + " " + UUID.randomUUID());
    }
}
