package com.qstory.backend.org.repository;

import com.qstory.backend.org.entity.ClassInvite;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassInviteRepository extends JpaRepository<ClassInvite, UUID> {

    Optional<ClassInvite> findByTokenHash(String tokenHash);
}
