package com.qstory.backend.org.dto;

import java.util.List;
import java.util.UUID;

/**
 * 학기 넘기기(076) - 반의 지금 학생 모두에게 MOVE(targetClassId 반으로) | KEEP(이 반에 남음) | GRADUATE(졸업) 중 하나를
 * 정한다. archiveClass면 끝난 뒤 이 반을 지난 반으로 보관한다(남는 학생이 없어야 한다).
 */
public record TermTransitionRequest(List<Decision> decisions, boolean archiveClass) {

    public record Decision(UUID studentId, String action, UUID targetClassId) {}
}
