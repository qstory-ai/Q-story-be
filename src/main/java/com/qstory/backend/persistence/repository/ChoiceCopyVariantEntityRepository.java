package com.qstory.backend.persistence.repository;

import com.qstory.backend.persistence.entity.ChoiceCopyVariantEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChoiceCopyVariantEntityRepository extends JpaRepository<ChoiceCopyVariantEntity, UUID> {

    List<ChoiceCopyVariantEntity> findAllByOrderByFamily_IdAscVariantIndexAsc();
}
