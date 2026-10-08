package com.qstory.backend.org.service;

import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassMembershipReason;
import com.qstory.backend.org.entity.TutorStudentClassHistory;
import com.qstory.backend.org.repository.TutorStudentClassHistoryRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생의 반 소속 이력(076)을 남긴다 - 반 코드로 들어올 때(JOINED), 원장이 옮길 때(MOVED), 학기를 넘겨 같은 반에 남을 때
 * (KEPT), 졸업할 때(GRADUATED). 지금 반 구간(ended_at null)은 학생마다 하나뿐이다.
 *
 * <p>학부모가 떠난 반의 반 수업 기록은 떠난 날까지만 보이는데(StoryCompletionRepository.CLASS_MEMBER_SEES_SESSION),
 * 그 "떠난 날"이 이 이력의 ended_at이다.
 */
@Service
public class StudentClassHistoryService {

    private final TutorStudentClassHistoryRepository repository;

    public StudentClassHistoryService(TutorStudentClassHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * 학생이 이 반에 들어왔다(반 코드 등록). 이미 이 반 구간이 열려 있으면 그대로 두고, 다른 반 구간이 열려 있으면
     * (076 이전 데이터 등) 그 구간을 MOVED로 닫는다.
     */
    @Transactional
    public void recordJoined(TutorStudent student, ClassGroup classGroup, Instant at) {
        List<TutorStudentClassHistory> open = repository.findByTutorStudent_IdAndEndedAtIsNull(student.getId());
        if (open.stream().anyMatch(entry -> entry.getClassGroup().getId().equals(classGroup.getId()))) {
            return;
        }
        closeAll(open, at, ClassMembershipReason.MOVED, null);
        repository.save(TutorStudentClassHistory.builder()
                .tutorStudent(student)
                .classGroup(classGroup)
                .startedAt(at)
                .reason(ClassMembershipReason.JOINED)
                .build());
    }

    /**
     * 지금 구간을 endReason으로 닫고 이 반의 새 구간을 startReason으로 연다. 새 구간을 돌려준다 - 알림 중복 방지 키에 id를 쓴다.
     * 지금 구간이 없으면(076 이전 데이터 누락) 새 구간만 연다.
     */
    @Transactional
    public TutorStudentClassHistory transfer(
            TutorStudent student, ClassGroup to, String endReason, String startReason, Instant at, UUID changedBy) {
        closeAll(repository.findByTutorStudent_IdAndEndedAtIsNull(student.getId()), at, endReason, changedBy);
        return repository.saveAndFlush(TutorStudentClassHistory.builder()
                .tutorStudent(student)
                .classGroup(to)
                .startedAt(at)
                .reason(startReason)
                .changedBy(changedBy)
                .build());
    }

    /**
     * 지금 구간을 닫는다(졸업). 닫은 구간을 돌려준다 - 지금 구간이 없었으면 졸업한 반의 닫힌 구간을 새로 남긴다
     * (지난 학생 명단과 학부모의 반 수업 열람 기한이 이 구간을 본다).
     */
    @Transactional
    public TutorStudentClassHistory close(
            TutorStudent student, ClassGroup classGroup, String endReason, Instant at, UUID changedBy) {
        List<TutorStudentClassHistory> open = repository.findByTutorStudent_IdAndEndedAtIsNull(student.getId());
        closeAll(open, at, endReason, changedBy);
        return open.stream()
                .filter(entry -> entry.getClassGroup().getId().equals(classGroup.getId()))
                .findFirst()
                .orElseGet(() -> repository.saveAndFlush(TutorStudentClassHistory.builder()
                        .tutorStudent(student)
                        .classGroup(classGroup)
                        .startedAt(student.getCreatedAt() != null ? student.getCreatedAt() : at)
                        .endedAt(at)
                        .reason(ClassMembershipReason.JOINED)
                        .endReason(endReason)
                        .changedBy(changedBy)
                        .build()));
    }

    @Transactional(readOnly = true)
    public List<TutorStudentClassHistory> list(UUID studentId) {
        return repository.findByTutorStudent_IdOrderByStartedAtAscIdAsc(studentId);
    }

    /** 이 반을 떠난 학생의 마지막 구간(최근에 떠난 순) - 지금 이 반 학생인지는 호출하는 쪽이 거른다. */
    @Transactional(readOnly = true)
    public List<TutorStudentClassHistory> endedInClass(UUID classId) {
        java.util.Map<UUID, TutorStudentClassHistory> latest = new java.util.LinkedHashMap<>();
        for (TutorStudentClassHistory entry : repository.findEndedInClass(classId)) {
            latest.putIfAbsent(entry.getTutorStudent().getId(), entry);
        }
        return List.copyOf(latest.values());
    }

    private void closeAll(List<TutorStudentClassHistory> open, Instant at, String endReason, UUID changedBy) {
        if (open.isEmpty()) return;
        for (TutorStudentClassHistory entry : open) {
            entry.setEndedAt(at);
            entry.setEndReason(endReason);
            if (changedBy != null) entry.setChangedBy(changedBy);
        }
        repository.saveAll(open);
        // 부분 유니크 인덱스(tutor_student_id where ended_at is null) - 새 구간을 넣기 전에 닫은 행을 먼저 내보낸다.
        repository.flush();
    }
}
