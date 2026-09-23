package com.qstory.backend.tutor.repository;

import com.qstory.backend.tutor.entity.TutorInvite;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TutorInviteRepository extends JpaRepository<TutorInvite, UUID> {

    Optional<TutorInvite> findByTokenHash(String tokenHash);

    Optional<TutorInvite> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /** 수락 시 같은 학생의 다른 미사용 초대를 함께 닫는다 - 두 번째 부모가 나중에 덮어쓰지 못하게. */
    List<TutorInvite> findByTutorStudent_IdAndUsedAtIsNull(UUID tutorStudentId);
}
