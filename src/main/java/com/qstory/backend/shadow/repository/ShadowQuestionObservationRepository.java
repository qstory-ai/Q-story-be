package com.qstory.backend.shadow.repository;

import com.qstory.backend.shadow.entity.ShadowQuestionObservation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShadowQuestionObservationRepository extends JpaRepository<ShadowQuestionObservation, UUID> {

    long countByCandidate_Id(UUID candidateId);

    @Query("select count(distinct o.session.id) from ShadowQuestionObservation o where o.candidate.id = :candidateId")
    long countDistinctSessionsByCandidateId(@Param("candidateId") UUID candidateId);

    /**
     * 후보를 검토로 올리는 기준(보안 점검 H2) - 로그인한 계정 수. 익명 베타 세션은 누구나 얼마든지 새로 만들 수 있어 세션 수만
     * 세면 한 사람이 같은 질문을 세 번 보내 후보를 검토 목록에 올릴 수 있었다.
     */
    @Query("select count(distinct o.session.userId) from ShadowQuestionObservation o "
            + "where o.candidate.id = :candidateId and o.session.userId is not null")
    long countDistinctUsersByCandidateId(@Param("candidateId") UUID candidateId);
}
