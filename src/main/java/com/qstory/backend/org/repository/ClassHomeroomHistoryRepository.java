package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.ClassHomeroomHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassHomeroomHistoryRepository extends JpaRepository<ClassHomeroomHistory, UUID> {

    /** 지금 담임 구간(ended_at null) - 반마다 많아야 하나(063 부분 유니크 인덱스). */
    List<ClassHomeroomHistory> findByClassGroup_IdAndEndedAtIsNull(UUID classGroupId);

    List<ClassHomeroomHistory> findByClassGroup_IdOrderByStartedAtAsc(UUID classGroupId);
}
