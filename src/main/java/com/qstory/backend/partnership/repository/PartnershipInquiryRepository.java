package com.qstory.backend.partnership.repository;

import com.qstory.backend.partnership.entity.PartnershipInquiry;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PartnershipInquiryRepository extends JpaRepository<PartnershipInquiry, UUID> {

    @Transactional
    @Modifying
    @Query("delete from PartnershipInquiry p where p.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
