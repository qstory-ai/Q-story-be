package com.qstory.backend.org.service;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassHomeroomHistory;
import com.qstory.backend.org.repository.ClassHomeroomHistoryRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반 담임 이력(063)을 남긴다 - 담임이 정해지거나(반 생성·배정·변경) 비워질 때(소속 해제·탈퇴) 호출한다.
 * 이력은 관리자가 보는 기록일 뿐, 수업·리포트의 귀속은 lesson.tutor_id와 story_completion.user_id가 정한다.
 */
@Service
public class ClassHomeroomHistoryService {

    private final ClassHomeroomHistoryRepository repository;

    public ClassHomeroomHistoryService(ClassHomeroomHistoryRepository repository) {
        this.repository = repository;
    }

    /** 지금 담임 구간을 닫고 새 담임 구간을 연다. */
    @Transactional
    public void start(ClassGroup classGroup, AppUser tutor, Instant at) {
        end(classGroup, at);
        repository.save(ClassHomeroomHistory.builder()
                .classGroup(classGroup)
                .tutor(tutor)
                .startedAt(at)
                .build());
    }

    /** 지금 담임 구간을 닫는다(담임 미정이 된다). */
    @Transactional
    public void end(ClassGroup classGroup, Instant at) {
        List<ClassHomeroomHistory> open = repository.findByClassGroup_IdAndEndedAtIsNull(classGroup.getId());
        open.forEach(entry -> entry.setEndedAt(at));
        if (!open.isEmpty()) {
            repository.saveAll(open);
            // 부분 유니크 인덱스(class_group_id where ended_at is null) - 새 구간을 넣기 전에 닫은 행을 먼저 내보낸다.
            repository.flush();
        }
    }

    /** 이 선생님이 이 반의 담임이었던 적이 있는가(지금 담임 포함, 076). */
    @Transactional(readOnly = true)
    public boolean hasLed(java.util.UUID classGroupId, java.util.UUID tutorId) {
        return repository.existsByClassGroup_IdAndTutor_Id(classGroupId, tutorId);
    }

    /** 이 선생님의 담임 이력 전체(오래된 것부터, 076). */
    @Transactional(readOnly = true)
    public List<ClassHomeroomHistory> listByTutor(java.util.UUID tutorId) {
        return repository.findByTutor_IdOrderByStartedAtAsc(tutorId);
    }

    @Transactional(readOnly = true)
    public List<ClassHomeroomHistory> list(ClassGroup classGroup) {
        return repository.findByClassGroup_IdOrderByStartedAtAsc(classGroup.getId());
    }
}
