package com.qstory.backend.storyreport.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 완주 저장 요청.
 * - tutorStudentId: 선생님이 자신이 등록한 학생과 진행한 세션일 때만 채운다.
 * - childId: 부모(PARENT) 계정에서 어느 아이 프로필로 진행한 세션인지 - 아이별 리포트 필터에 쓴다.
 *   선생님 세션이나 아이 프로필 없이 진행한 경우는 null. 두 필드는 배타적으로 사용하지
 *   않아도 되지만(실제로는 한 세션이 두 축을 동시에 만족하는 경우가 거의 없다) 서로 필수 관계도
 *   아니다 - 서비스는 각각을 caller가 소유했는지만 검증한다.
 */
public record RecordStoryCompletionRequest(
        String storyId,
        Integer durationSeconds,
        List<Map<String, Object>> outcomes,
        UUID tutorStudentId,
        UUID childId) {}
