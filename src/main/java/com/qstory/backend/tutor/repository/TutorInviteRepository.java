package com.qstory.backend.tutor.repository;

import com.qstory.backend.tutor.entity.TutorInvite;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TutorInviteRepository extends JpaRepository<TutorInvite, UUID> {

    Optional<TutorInvite> findByTokenHash(String tokenHash);

    Optional<TutorInvite> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);
}
