package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.ShadowQuestionObservation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShadowQuestionObservationRepository extends JpaRepository<ShadowQuestionObservation, UUID> {

    List<ShadowQuestionObservation> findByCandidate_Id(UUID candidateId);

    long countByCandidate_Id(UUID candidateId);

    @Query("select count(distinct o.session.id) from ShadowQuestionObservation o where o.candidate.id = :candidateId")
    long countDistinctSessionsByCandidateId(@Param("candidateId") UUID candidateId);
}
