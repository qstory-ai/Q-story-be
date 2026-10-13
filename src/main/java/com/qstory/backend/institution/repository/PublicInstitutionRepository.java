package com.qstory.backend.institution.repository;

import com.qstory.backend.institution.entity.PublicInstitution;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicInstitutionRepository extends JpaRepository<PublicInstitution, UUID> {}
